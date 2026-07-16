package dev.hytixmc.arctic.minestom;

import dev.hytixmc.arctic.ArcticChunkEncoding;
import dev.hytixmc.arctic.ArcticFormatException;
import dev.hytixmc.arctic.ArcticWorldAccess;
import dev.hytixmc.arctic.format.ArcticFile;
import dev.hytixmc.arctic.format.StoredChunk;
import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;

import java.io.IOException;

/** Internal bridge kept public only to avoid exposing Minestom details from the format package. */
public final class MinestomChunkIO {
    private MinestomChunkIO() {
    }

    public static StoredChunk encode(Chunk chunk, ArcticChunkEncoding encoding,
                                     ArcticWorldAccess worldAccess) throws IOException {
        ChunkModel model = MinestomChunkAdapter.snapshot(chunk, worldAccess);
        byte[] payload = ChunkPayloadCodec.forEncoding(encoding).encode(model);
        return new StoredChunk(chunk.getChunkX(), chunk.getChunkZ(), encoding,
                ArcticFile.CHUNK_SCHEMA_VERSION, MinecraftServer.DATA_VERSION, payload);
    }

    public static Chunk decode(Instance instance, StoredChunk stored,
                               ArcticWorldAccess worldAccess) throws IOException {
        if (stored.schemaVersion() != ArcticFile.CHUNK_SCHEMA_VERSION) {
            throw new ArcticFormatException("Unsupported Arctic chunk schema: " + stored.schemaVersion());
        }
        if (stored.dataVersion() > MinecraftServer.DATA_VERSION) {
            throw new ArcticFormatException("Chunk data version " + stored.dataVersion()
                    + " is newer than supported version " + MinecraftServer.DATA_VERSION);
        }
        ChunkModel model = ChunkPayloadCodec.forEncoding(stored.encoding()).decode(stored.payload());
        if (model.x() != stored.x() || model.z() != stored.z()) {
            throw new ArcticFormatException("Chunk payload coordinates do not match its record key");
        }
        return MinestomChunkAdapter.createChunk(instance, model, worldAccess);
    }
}
