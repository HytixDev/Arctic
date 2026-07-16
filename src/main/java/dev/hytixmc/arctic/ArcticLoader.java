package dev.hytixmc.arctic;

import dev.hytixmc.arctic.format.ArcticFile;
import dev.hytixmc.arctic.format.StoredChunk;
import dev.hytixmc.arctic.minestom.MinestomChunkIO;
import dev.hytixmc.arctic.minestom.NbtIo;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.ChunkLoader;
import net.minestom.server.instance.Instance;
import net.minestom.server.world.DimensionType;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Lazy, streamed Minestom {@link ChunkLoader} backed by one {@code .arctic} file.
 *
 * <p>Only the coordinate index is resident. Every chunk is loaded with one positional read and is
 * saved as an independent append-only transaction. Call {@link #close()} during server shutdown.</p>
 */
public final class ArcticLoader implements ChunkLoader, AutoCloseable {
    private static final int WORLD_SCHEMA_VERSION = 1;

    private final ArcticOptions options;
    private final ArcticWorldAccess worldAccess;
    private final ArcticFile file;

    public ArcticLoader(Path path) throws IOException {
        this(path, ArcticOptions.defaults(), ArcticWorldAccess.DEFAULT);
    }

    public ArcticLoader(Path path, ArcticOptions options) throws IOException {
        this(path, options, ArcticWorldAccess.DEFAULT);
    }

    public ArcticLoader(Path path, ArcticOptions options, ArcticWorldAccess worldAccess)
            throws IOException {
        this.options = Objects.requireNonNull(options, "options");
        this.worldAccess = Objects.requireNonNull(worldAccess, "worldAccess");
        this.file = ArcticFile.open(Objects.requireNonNull(path, "path"), options);
    }

    public static Builder builder(Path path) {
        return new Builder(path);
    }

    public ArcticFile file() {
        return file;
    }

    public ArcticOptions options() {
        return options;
    }

    @Override
    public void loadInstance(Instance instance) {
        try {
            byte[] payload = file.readWorldData();
            if (payload.length == 0) return;
            CompoundBinaryTag root = NbtIo.read(payload);
            int schema = root.getInt("ArcticWorldSchema");
            if (schema != WORLD_SCHEMA_VERSION) {
                throw new ArcticFormatException("Unsupported Arctic world metadata schema: " + schema);
            }
            int dataVersion = root.getInt("DataVersion");
            if (dataVersion > MinecraftServer.DATA_VERSION) {
                throw new ArcticFormatException("World data version " + dataVersion
                        + " is newer than supported version " + MinecraftServer.DATA_VERSION);
            }
            DimensionType dimension = instance.getCachedDimensionType();
            int minSection = dimension.minY() / Chunk.CHUNK_SECTION_SIZE;
            int maxSection = dimension.maxY() / Chunk.CHUNK_SECTION_SIZE;
            if (root.getInt("MinSection") != minSection || root.getInt("MaxSection") != maxSection) {
                throw new ArcticFormatException("Arctic world height does not match the instance dimension");
            }
            CompoundBinaryTag userData = root.get("UserData") instanceof CompoundBinaryTag compound
                    ? compound : CompoundBinaryTag.empty();
            worldAccess.loadWorldData(instance, userData);
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to load Arctic world metadata", exception);
        }
    }

    @Override
    public @Nullable Chunk loadChunk(Instance instance, int chunkX, int chunkZ) {
        try {
            var stored = file.readChunk(chunkX, chunkZ);
            return stored.isEmpty() ? null : MinestomChunkIO.decode(instance, stored.orElseThrow(), worldAccess);
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to load Arctic chunk " + chunkX + ',' + chunkZ, exception);
        }
    }

    @Override
    public void saveInstance(Instance instance) {
        try {
            DimensionType dimension = instance.getCachedDimensionType();
            CompoundBinaryTag userData = Objects.requireNonNull(worldAccess.saveWorldData(instance),
                    "ArcticWorldAccess.saveWorldData returned null");
            CompoundBinaryTag root = CompoundBinaryTag.builder()
                    .putInt("ArcticWorldSchema", WORLD_SCHEMA_VERSION)
                    .putInt("DataVersion", MinecraftServer.DATA_VERSION)
                    .putInt("MinSection", dimension.minY() / Chunk.CHUNK_SECTION_SIZE)
                    .putInt("MaxSection", dimension.maxY() / Chunk.CHUNK_SECTION_SIZE)
                    .put("UserData", userData)
                    .build();
            file.writeWorldData(NbtIo.write(root));
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to save Arctic world metadata", exception);
        }
    }

    @Override
    public void saveChunk(Chunk chunk) {
        saveChunks(List.of(chunk));
    }

    @Override
    public void saveChunks(Collection<Chunk> chunks) {
        Objects.requireNonNull(chunks, "chunks");
        if (chunks.isEmpty()) return;
        try {
            List<StoredChunk> batch = new ArrayList<>(Math.min(chunks.size(),
                    options.saveBatchChunkLimit()));
            long batchBytes = 0;
            for (Chunk chunk : chunks) {
                StoredChunk stored = MinestomChunkIO.encode(
                        Objects.requireNonNull(chunk, "chunks contains null"),
                        options.chunkEncoding(), worldAccess);
                int payloadBytes = stored.payloadLength();
                if (!batch.isEmpty() && (batch.size() >= options.saveBatchChunkLimit()
                        || batchBytes + payloadBytes > options.saveBatchByteLimit())) {
                    file.writeChunks(batch);
                    batch.clear();
                    batchBytes = 0;
                }
                batch.add(stored);
                batchBytes += payloadBytes;
            }
            if (!batch.isEmpty()) file.writeChunks(batch);
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to save Arctic chunk batch", exception);
        }
    }

    @Override
    public boolean supportsParallelLoading() {
        return true;
    }

    @Override
    public boolean supportsParallelSaving() {
        return true;
    }

    /** Appends a durable tombstone for the supplied chunk coordinates. */
    public boolean deleteChunk(int chunkX, int chunkZ) throws IOException {
        return file.deleteChunk(chunkX, chunkZ);
    }

    /** Publishes a full coordinate index checkpoint, reducing work on the next open. */
    public void checkpoint() throws IOException {
        file.checkpoint();
    }

    /** Copies all live data into a new compacted file. */
    public void compactTo(Path destination) throws IOException {
        file.compactTo(destination);
    }

    @Override
    public void close() throws IOException {
        file.close();
    }

    public static final class Builder {
        private final Path path;
        private ArcticOptions options = ArcticOptions.defaults();
        private ArcticWorldAccess worldAccess = ArcticWorldAccess.DEFAULT;

        private Builder(Path path) {
            this.path = Objects.requireNonNull(path, "path");
        }

        public Builder options(ArcticOptions options) {
            this.options = Objects.requireNonNull(options, "options");
            return this;
        }

        public Builder worldAccess(ArcticWorldAccess worldAccess) {
            this.worldAccess = Objects.requireNonNull(worldAccess, "worldAccess");
            return this;
        }

        public ArcticLoader open() throws IOException {
            return new ArcticLoader(path, options, worldAccess);
        }
    }
}
