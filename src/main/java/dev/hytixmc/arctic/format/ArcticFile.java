package dev.hytixmc.arctic.format;

import com.github.luben.zstd.Zstd;
import dev.hytixmc.arctic.ArcticChunkEncoding;
import dev.hytixmc.arctic.ArcticCompression;
import dev.hytixmc.arctic.ArcticFormatException;
import dev.hytixmc.arctic.ArcticOptions;
import dev.hytixmc.arctic.ArcticSyncMode;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.zip.CRC32C;

/**
 * Seekable append-only storage for an Arctic world.
 *
 * <p>Chunk bodies are never loaded during open. The file keeps only a compact coordinate-to-offset
 * index in memory and reads chunk records using positional I/O. Writes are committed by alternating
 * between two checksummed superblocks, so an interrupted write leaves the previous generation
 * readable.</p>
 */
public final class ArcticFile implements AutoCloseable {
    public static final int CONTAINER_VERSION = 1;
    public static final int CHUNK_SCHEMA_VERSION = 1;

    private static final byte[] FILE_MAGIC = {'A', 'R', 'C', 'T', 'I', 'C', '\r', '\n'};
    private static final int RECORD_MAGIC = 0x41524352; // ARCR
    private static final int INDEX_MAGIC = 0x41494458; // AIDX
    private static final int INDEX_VERSION = 1;

    private static final int SUPERBLOCK_SIZE = 4096;
    private static final int SUPERBLOCK_CRC_OFFSET = SUPERBLOCK_SIZE - Integer.BYTES;
    private static final long DATA_START = SUPERBLOCK_SIZE * 2L;
    private static final int RECORD_HEADER_SIZE = 80;
    private static final int INDEX_PREFIX_SIZE = 16;
    private static final int INDEX_ENTRY_SIZE = 32;

    private static final byte TYPE_CHUNK = 1;
    private static final byte TYPE_TOMBSTONE = 2;
    private static final byte TYPE_WORLD_DATA = 3;
    private static final byte TYPE_INDEX = 4;

    private final Path path;
    private final ArcticOptions options;
    private final FileChannel channel;
    private final FileLock processLock;
    private final UUID fileId;
    private final ConcurrentHashMap<ChunkKey, IndexEntry> chunkIndex = new ConcurrentHashMap<>();
    private final ReentrantLock writeLock = new ReentrantLock();
    private final ReentrantReadWriteLock stateLock = new ReentrantReadWriteLock();

    private volatile boolean closed;
    private long generation;
    private long committedLength;
    private long checkpointOffset;
    private long checkpointLength;
    private long worldDataOffset;
    private long worldDataLength;
    private int activeSuperblock;
    private int recordsSinceCheckpoint;

    private ArcticFile(Path path, ArcticOptions options, FileChannel channel, FileLock processLock,
                       Superblock superblock, int activeSuperblock) throws IOException {
        this.path = path;
        this.options = options;
        this.channel = channel;
        this.processLock = processLock;
        this.fileId = superblock.fileId();
        this.generation = superblock.generation();
        this.committedLength = superblock.committedLength();
        this.checkpointOffset = superblock.checkpointOffset();
        this.checkpointLength = superblock.checkpointLength();
        this.worldDataOffset = superblock.worldDataOffset();
        this.worldDataLength = superblock.worldDataLength();
        this.activeSuperblock = activeSuperblock;

        if (channel.size() < committedLength) {
            throw new ArcticFormatException("Arctic file is truncated: committed length is "
                    + committedLength + " but file length is " + channel.size());
        }
        rebuildIndex();
        if (channel.size() > committedLength) {
            channel.truncate(committedLength);
        }
    }

    /** Opens an existing file or creates a new one. A process-exclusive file lock is retained. */
    public static ArcticFile open(Path path, ArcticOptions options) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        Path absolutePath = path.toAbsolutePath().normalize();
        Path parent = absolutePath.getParent();
        if (parent != null) Files.createDirectories(parent);

        FileChannel channel = FileChannel.open(absolutePath,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        FileLock processLock = null;
        try {
            try {
                processLock = channel.tryLock();
            } catch (OverlappingFileLockException exception) {
                throw new IOException("Arctic world is already open in this process: " + absolutePath,
                        exception);
            }
            if (processLock == null) {
                throw new IOException("Arctic world is already open by another process: " + absolutePath);
            }

            if (channel.size() == 0) {
                initialize(channel, UUID.randomUUID(), options.syncMode());
            } else if (channel.size() < DATA_START) {
                throw new ArcticFormatException("File is too short to contain an Arctic superblock pair");
            }

            Superblock first = readSuperblock(channel, 0);
            Superblock second = readSuperblock(channel, 1);
            if (first == null && second == null) {
                throw new ArcticFormatException("No valid Arctic superblock found");
            }
            if (first != null && second != null && !first.fileId().equals(second.fileId())) {
                throw new ArcticFormatException("Arctic superblocks contain different file identifiers");
            }

            List<SuperblockCandidate> candidates = new ArrayList<>(2);
            if (first != null) candidates.add(new SuperblockCandidate(first, 0));
            if (second != null) candidates.add(new SuperblockCandidate(second, 1));
            candidates.sort(Comparator.comparingLong(
                    (SuperblockCandidate candidate) -> candidate.superblock().generation()).reversed());

            ArcticFormatException newestFailure = null;
            long physicalLength = channel.size();
            for (SuperblockCandidate candidate : candidates) {
                if (!candidate.superblock().isPlausible(physicalLength)) continue;
                try {
                    return new ArcticFile(absolutePath, options, channel, processLock,
                            candidate.superblock(), candidate.slot());
                } catch (ArcticFormatException exception) {
                    if (newestFailure == null) newestFailure = exception;
                }
            }
            if (newestFailure != null) {
                throw new ArcticFormatException("No fully usable Arctic generation found", newestFailure);
            }
            throw new ArcticFormatException("All Arctic superblocks point outside the physical file");
        } catch (Throwable throwable) {
            if (processLock != null) {
                try {
                    processLock.close();
                } catch (IOException closeFailure) {
                    throwable.addSuppressed(closeFailure);
                }
            }
            try {
                channel.close();
            } catch (IOException closeFailure) {
                throwable.addSuppressed(closeFailure);
            }
            throw throwable;
        }
    }

    public Path path() {
        return path;
    }

    public UUID fileId() {
        return fileId;
    }

    public long generation() {
        stateLock.readLock().lock();
        try {
            return generation;
        } finally {
            stateLock.readLock().unlock();
        }
    }

    public long chunkCount() {
        stateLock.readLock().lock();
        try {
            return chunkIndex.mappingCount();
        } finally {
            stateLock.readLock().unlock();
        }
    }

    public boolean containsChunk(int chunkX, int chunkZ) {
        stateLock.readLock().lock();
        try {
            ensureOpenUnchecked();
            return chunkIndex.containsKey(new ChunkKey(chunkX, chunkZ));
        } finally {
            stateLock.readLock().unlock();
        }
    }

    /** Reads and verifies one chunk record without changing the shared channel position. */
    public Optional<StoredChunk> readChunk(int chunkX, int chunkZ) throws IOException {
        stateLock.readLock().lock();
        try {
            ensureOpen();
            IndexEntry entry = chunkIndex.get(new ChunkKey(chunkX, chunkZ));
            if (entry == null) return Optional.empty();

            RecordHeader header = readRecordHeader(entry.offset());
            if (header.type() != TYPE_CHUNK || header.x() != chunkX || header.z() != chunkZ
                    || header.totalLength() != entry.length()
                    || header.generation() != entry.generation()) {
                throw new ArcticFormatException("Chunk index points to the wrong record at " + entry.offset());
            }
            byte[] payload = readDecodedPayload(header, entry.offset(), options.maxCompressedChunkBytes(),
                    options.maxUncompressedChunkBytes());
            final ArcticChunkEncoding encoding;
            try {
                encoding = ArcticChunkEncoding.fromId(Byte.toUnsignedInt(header.encoding()));
            } catch (IllegalArgumentException exception) {
                throw new ArcticFormatException("Unsupported chunk encoding at " + entry.offset(), exception);
            }
            return Optional.of(new StoredChunk(chunkX, chunkZ, encoding, header.schemaVersion(),
                    header.dataVersion(), payload));
        } finally {
            stateLock.readLock().unlock();
        }
    }

    /**
     * Atomically appends and publishes all supplied chunks as one generation.
     * Duplicate coordinates are rejected to avoid ambiguous batch ordering.
     */
    public void writeChunks(Collection<StoredChunk> chunks) throws IOException {
        Objects.requireNonNull(chunks, "chunks");
        if (chunks.isEmpty()) return;
        ensureOpen();

        List<PreparedRecord> prepared = new ArrayList<>(chunks.size());
        var coordinates = ConcurrentHashMap.<ChunkKey>newKeySet(chunks.size());
        for (StoredChunk chunk : chunks) {
            Objects.requireNonNull(chunk, "chunks contains null");
            ChunkKey key = new ChunkKey(chunk.x(), chunk.z());
            if (!coordinates.add(key)) {
                throw new IllegalArgumentException("Duplicate chunk in batch: " + chunk.x() + "," + chunk.z());
            }
            if (chunk.payloadLength() > options.maxUncompressedChunkBytes()) {
                throw new IOException("Chunk " + chunk.x() + "," + chunk.z() + " exceeds the uncompressed limit");
            }
            prepared.add(prepareChunk(chunk));
        }

        writeLock.lock();
        stateLock.writeLock().lock();
        try {
            ensureOpen();
            long oldLength = committedLength;
            long targetGeneration = Math.incrementExact(generation);
            List<RollbackEntry> rollback = new ArrayList<>(prepared.size());
            long appendOffset = oldLength;
            try {
                channel.truncate(oldLength);
                for (PreparedRecord record : prepared) {
                    RecordHeader header = record.header(targetGeneration);
                    long recordLength = writeRecord(appendOffset, header, record.storedPayload());
                    ChunkKey key = new ChunkKey(header.x(), header.z());
                    IndexEntry replacement = new IndexEntry(appendOffset, recordLength, targetGeneration);
                    rollback.add(new RollbackEntry(key, chunkIndex.put(key, replacement)));
                    appendOffset = Math.addExact(appendOffset, recordLength);
                }

                int newRecordCount = Math.addExact(recordsSinceCheckpoint, prepared.size());
                long newCheckpointOffset = checkpointOffset;
                long newCheckpointLength = checkpointLength;
                if (newRecordCount >= options.checkpointInterval()) {
                    newCheckpointOffset = appendOffset;
                    newCheckpointLength = writeCheckpoint(appendOffset, targetGeneration);
                    appendOffset = Math.addExact(appendOffset, newCheckpointLength);
                    newRecordCount = 0;
                }

                publish(targetGeneration, appendOffset, newCheckpointOffset, newCheckpointLength,
                        worldDataOffset, worldDataLength);
                recordsSinceCheckpoint = newRecordCount;
            } catch (Throwable throwable) {
                if (!closed) {
                    rollback(rollback);
                    truncateAfterFailure(oldLength, throwable);
                }
                throw throwable;
            }
        } finally {
            stateLock.writeLock().unlock();
            writeLock.unlock();
        }
    }

    /** Atomically removes a chunk from the current index by appending a tombstone. */
    public boolean deleteChunk(int chunkX, int chunkZ) throws IOException {
        ensureOpen();
        writeLock.lock();
        stateLock.writeLock().lock();
        try {
            ensureOpen();
            ChunkKey key = new ChunkKey(chunkX, chunkZ);
            IndexEntry oldEntry = chunkIndex.get(key);
            if (oldEntry == null) return false;

            long oldLength = committedLength;
            long targetGeneration = Math.incrementExact(generation);
            try {
                channel.truncate(oldLength);
                RecordHeader tombstone = new RecordHeader(TYPE_TOMBSTONE, (byte) 0, (byte) 0,
                        1, 0, chunkX, chunkZ, targetGeneration, 0, 0, 0);
                long recordLength = writeRecord(oldLength, tombstone, new byte[0]);
                chunkIndex.remove(key);
                long appendOffset = Math.addExact(oldLength, recordLength);
                int newRecordCount = Math.incrementExact(recordsSinceCheckpoint);
                long newCheckpointOffset = checkpointOffset;
                long newCheckpointLength = checkpointLength;
                if (newRecordCount >= options.checkpointInterval()) {
                    newCheckpointOffset = appendOffset;
                    newCheckpointLength = writeCheckpoint(appendOffset, targetGeneration);
                    appendOffset = Math.addExact(appendOffset, newCheckpointLength);
                    newRecordCount = 0;
                }
                publish(targetGeneration, appendOffset, newCheckpointOffset, newCheckpointLength,
                        worldDataOffset, worldDataLength);
                recordsSinceCheckpoint = newRecordCount;
                return true;
            } catch (Throwable throwable) {
                if (!closed) {
                    chunkIndex.put(key, oldEntry);
                    truncateAfterFailure(oldLength, throwable);
                }
                throw throwable;
            }
        } finally {
            stateLock.writeLock().unlock();
            writeLock.unlock();
        }
    }

    /** Reads the latest opaque world metadata record. */
    public byte[] readWorldData() throws IOException {
        stateLock.readLock().lock();
        try {
            ensureOpen();
            long offset = worldDataOffset;
            if (offset == 0) return new byte[0];
            RecordHeader header = readRecordHeader(offset);
            if (header.type() != TYPE_WORLD_DATA || header.totalLength() != worldDataLength) {
                throw new ArcticFormatException("World metadata pointer is invalid");
            }
            return readDecodedPayload(header, offset, options.maxWorldDataBytes(),
                    options.maxWorldDataBytes());
        } finally {
            stateLock.readLock().unlock();
        }
    }

    /** Atomically replaces the opaque world metadata. */
    public void writeWorldData(byte[] data) throws IOException {
        Objects.requireNonNull(data, "data");
        if (data.length > options.maxWorldDataBytes()) {
            throw new IOException("World metadata exceeds the configured limit");
        }
        ensureOpen();
        PreparedPayload payload = compress(data);

        writeLock.lock();
        stateLock.writeLock().lock();
        try {
            ensureOpen();
            long oldLength = committedLength;
            long targetGeneration = Math.incrementExact(generation);
            try {
                channel.truncate(oldLength);
                RecordHeader header = new RecordHeader(TYPE_WORLD_DATA, (byte) 0,
                        (byte) payload.compression().id(), 1, 0, 0, 0, targetGeneration,
                        data.length, payload.bytes().length, checksum(payload.bytes()));
                long length = writeRecord(oldLength, header, payload.bytes());
                publish(targetGeneration, Math.addExact(oldLength, length), checkpointOffset,
                        checkpointLength, oldLength, length);
            } catch (Throwable throwable) {
                if (!closed) truncateAfterFailure(oldLength, throwable);
                throw throwable;
            }
        } finally {
            stateLock.writeLock().unlock();
            writeLock.unlock();
        }
    }

    /** Writes a fresh index checkpoint and publishes it as a new generation. */
    public void checkpoint() throws IOException {
        ensureOpen();
        writeLock.lock();
        stateLock.writeLock().lock();
        try {
            ensureOpen();
            long oldLength = committedLength;
            long targetGeneration = Math.incrementExact(generation);
            try {
                channel.truncate(oldLength);
                long length = writeCheckpoint(oldLength, targetGeneration);
                publish(targetGeneration, Math.addExact(oldLength, length), oldLength, length,
                        worldDataOffset, worldDataLength);
                recordsSinceCheckpoint = 0;
            } catch (Throwable throwable) {
                if (!closed) truncateAfterFailure(oldLength, throwable);
                throw throwable;
            }
        } finally {
            stateLock.writeLock().unlock();
            writeLock.unlock();
        }
    }

    /** Copies only live records to another Arctic file. The destination must not be this file. */
    public void compactTo(Path destination) throws IOException {
        Objects.requireNonNull(destination, "destination");
        Path normalized = destination.toAbsolutePath().normalize();
        if (normalized.equals(path)) throw new IllegalArgumentException("Destination must differ from source");
        if (Files.exists(normalized)) throw new IOException("Compaction destination already exists: " + normalized);

        stateLock.readLock().lock();
        try (ArcticFile compacted = ArcticFile.open(normalized, options)) {
            byte[] worldData = readWorldData();
            if (worldData.length != 0) compacted.writeWorldData(worldData);

            List<ChunkKey> keys = new ArrayList<>(chunkIndex.keySet());
            keys.sort(Comparator.comparingInt(ChunkKey::x).thenComparingInt(ChunkKey::z));
            int batchSize = 256;
            for (int start = 0; start < keys.size(); start += batchSize) {
                int end = Math.min(start + batchSize, keys.size());
                List<StoredChunk> batch = new ArrayList<>(end - start);
                for (int index = start; index < end; index++) {
                    ChunkKey key = keys.get(index);
                    Optional<StoredChunk> chunk = readChunk(key.x(), key.z());
                    chunk.ifPresent(batch::add);
                }
                compacted.writeChunks(batch);
            }
            compacted.checkpoint();
        } finally {
            stateLock.readLock().unlock();
        }
    }

    @Override
    public void close() throws IOException {
        writeLock.lock();
        stateLock.writeLock().lock();
        try {
            if (closed) return;
            closed = true;
            IOException failure = null;
            try {
                processLock.close();
            } catch (IOException exception) {
                failure = exception;
            }
            try {
                channel.close();
            } catch (IOException exception) {
                if (failure == null) failure = exception;
                else failure.addSuppressed(exception);
            }
            if (failure != null) throw failure;
        } finally {
            stateLock.writeLock().unlock();
            writeLock.unlock();
        }
    }

    private PreparedRecord prepareChunk(StoredChunk chunk) throws IOException {
        byte[] raw = chunk.payload();
        PreparedPayload payload = compress(raw);
        if (payload.bytes().length > options.maxCompressedChunkBytes()) {
            throw new IOException("Chunk " + chunk.x() + "," + chunk.z() + " exceeds the compressed limit");
        }
        return new PreparedRecord(TYPE_CHUNK, (byte) chunk.encoding().id(),
                (byte) payload.compression().id(), chunk.schemaVersion(), chunk.dataVersion(),
                chunk.x(), chunk.z(), raw.length, payload.bytes());
    }

    private PreparedPayload compress(byte[] raw) throws IOException {
        if (options.compression() == ArcticCompression.NONE || raw.length == 0) {
            return new PreparedPayload(ArcticCompression.NONE, Arrays.copyOf(raw, raw.length));
        }
        byte[] compressed;
        try {
            compressed = Zstd.compress(raw, options.compressionLevel());
        } catch (RuntimeException exception) {
            throw new IOException("Zstd compression failed", exception);
        }
        if (compressed.length >= raw.length) {
            return new PreparedPayload(ArcticCompression.NONE, Arrays.copyOf(raw, raw.length));
        }
        return new PreparedPayload(ArcticCompression.ZSTD, compressed);
    }

    private byte[] readDecodedPayload(RecordHeader header, long recordOffset,
                                      int maxStoredBytes, int maxDecodedBytes) throws IOException {
        if (header.storedLength() < 0 || header.storedLength() > maxStoredBytes
                || header.uncompressedLength() < 0 || header.uncompressedLength() > maxDecodedBytes) {
            throw new ArcticFormatException("Record at " + recordOffset + " exceeds configured size limits");
        }
        int storedLength = Math.toIntExact(header.storedLength());
        byte[] stored = new byte[storedLength];
        readFully(channel, ByteBuffer.wrap(stored), recordOffset + RECORD_HEADER_SIZE);
        if (checksum(stored) != header.payloadChecksum()) {
            throw new ArcticFormatException("Payload checksum mismatch at record " + recordOffset);
        }

        final ArcticCompression compression;
        try {
            compression = ArcticCompression.fromId(Byte.toUnsignedInt(header.compression()));
        } catch (IllegalArgumentException exception) {
            throw new ArcticFormatException("Unsupported compression at record " + recordOffset, exception);
        }
        return switch (compression) {
            case NONE -> {
                if (header.uncompressedLength() != stored.length) {
                    throw new ArcticFormatException("Uncompressed record has inconsistent lengths at " + recordOffset);
                }
                yield stored;
            }
            case ZSTD -> {
                int decodedLength = Math.toIntExact(header.uncompressedLength());
                byte[] decoded;
                try {
                    decoded = Zstd.decompress(stored, decodedLength);
                } catch (RuntimeException exception) {
                    throw new ArcticFormatException("Zstd decompression failed at record " + recordOffset, exception);
                }
                if (decoded.length != decodedLength) {
                    throw new ArcticFormatException("Zstd output length mismatch at record " + recordOffset);
                }
                yield decoded;
            }
        };
    }

    private void rebuildIndex() throws IOException {
        long scanOffset = DATA_START;
        if (checkpointOffset != 0) {
            if (checkpointOffset < DATA_START || checkpointLength < RECORD_HEADER_SIZE
                    || checkpointLength > committedLength - DATA_START
                    || checkpointOffset > committedLength - checkpointLength) {
                throw new ArcticFormatException("Checkpoint pointer lies outside the committed file");
            }
            loadCheckpoint(checkpointOffset, checkpointLength);
            scanOffset = Math.addExact(checkpointOffset, checkpointLength);
        }

        int deltaCount = 0;
        while (scanOffset < committedLength) {
            RecordHeader header = readRecordHeader(scanOffset);
            if (header.generation() > generation) {
                throw new ArcticFormatException("Record generation exceeds the active superblock at " + scanOffset);
            }
            switch (header.type()) {
                case TYPE_CHUNK -> {
                    chunkIndex.put(new ChunkKey(header.x(), header.z()),
                            new IndexEntry(scanOffset, header.totalLength(), header.generation()));
                    deltaCount++;
                }
                case TYPE_TOMBSTONE -> {
                    chunkIndex.remove(new ChunkKey(header.x(), header.z()));
                    deltaCount++;
                }
                case TYPE_WORLD_DATA, TYPE_INDEX -> {
                    // Located by superblock or superseded by a later checkpoint.
                }
                default -> throw new ArcticFormatException("Unknown record type " + header.type()
                        + " at " + scanOffset);
            }
            scanOffset = Math.addExact(scanOffset, header.totalLength());
        }
        if (scanOffset != committedLength) {
            throw new ArcticFormatException("Committed file ends in the middle of a record");
        }
        recordsSinceCheckpoint = deltaCount;
    }

    private void loadCheckpoint(long offset, long expectedLength) throws IOException {
        RecordHeader header = readRecordHeader(offset);
        if (header.type() != TYPE_INDEX || header.compression() != 0
                || header.totalLength() != expectedLength) {
            throw new ArcticFormatException("Invalid index checkpoint record at " + offset);
        }
        if (header.storedLength() < INDEX_PREFIX_SIZE
                || (header.storedLength() - INDEX_PREFIX_SIZE) % INDEX_ENTRY_SIZE != 0) {
            throw new ArcticFormatException("Invalid index checkpoint length at " + offset);
        }

        CRC32C checksum = new CRC32C();
        ByteBuffer prefix = allocate(INDEX_PREFIX_SIZE);
        readFully(channel, prefix, offset + RECORD_HEADER_SIZE);
        updateChecksum(checksum, prefix.array());
        prefix.flip();
        if (prefix.getInt() != INDEX_MAGIC || prefix.getInt() != INDEX_VERSION) {
            throw new ArcticFormatException("Unsupported index checkpoint at " + offset);
        }
        long entryCount = prefix.getLong();
        if (entryCount < 0) throw new ArcticFormatException("Negative index entry count");
        final long expectedPayloadLength;
        try {
            expectedPayloadLength = Math.addExact(INDEX_PREFIX_SIZE,
                    Math.multiplyExact(entryCount, INDEX_ENTRY_SIZE));
        } catch (ArithmeticException exception) {
            throw new ArcticFormatException("Index entry count overflows its payload length", exception);
        }
        if (expectedPayloadLength != header.storedLength()) {
            throw new ArcticFormatException("Index entry count does not match its payload length");
        }

        long entryOffset = offset + RECORD_HEADER_SIZE + INDEX_PREFIX_SIZE;
        ByteBuffer entryBuffer = allocate(INDEX_ENTRY_SIZE);
        for (long index = 0; index < entryCount; index++) {
            entryBuffer.clear();
            readFully(channel, entryBuffer, entryOffset);
            updateChecksum(checksum, entryBuffer.array());
            entryBuffer.flip();
            int x = entryBuffer.getInt();
            int z = entryBuffer.getInt();
            long recordOffset = entryBuffer.getLong();
            long recordLength = entryBuffer.getLong();
            long recordGeneration = entryBuffer.getLong();
            if (recordOffset < DATA_START || recordLength < RECORD_HEADER_SIZE
                    || recordLength > committedLength - DATA_START
                    || recordOffset > committedLength - recordLength
                    || recordGeneration > generation) {
                throw new ArcticFormatException("Checkpoint contains an invalid chunk record pointer");
            }
            chunkIndex.put(new ChunkKey(x, z),
                    new IndexEntry(recordOffset, recordLength, recordGeneration));
            entryOffset += INDEX_ENTRY_SIZE;
        }
        if ((int) checksum.getValue() != header.payloadChecksum()) {
            throw new ArcticFormatException("Index checkpoint checksum mismatch at " + offset);
        }
    }

    private long writeCheckpoint(long offset, long targetGeneration) throws IOException {
        long payloadLength = Math.addExact(INDEX_PREFIX_SIZE,
                Math.multiplyExact(chunkIndex.mappingCount(), INDEX_ENTRY_SIZE));
        RecordHeader provisional = new RecordHeader(TYPE_INDEX, (byte) 0, (byte) 0,
                INDEX_VERSION, 0, 0, 0, targetGeneration, payloadLength, payloadLength, 0);
        writeFully(channel, encodeRecordHeader(provisional), offset);

        CRC32C crc = new CRC32C();
        ByteBuffer buffer = ByteBuffer.allocate(64 * 1024).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(INDEX_MAGIC).putInt(INDEX_VERSION).putLong(chunkIndex.mappingCount());
        long payloadOffset = offset + RECORD_HEADER_SIZE;
        payloadOffset = flushCheckpointBuffer(buffer, crc, payloadOffset);

        for (var entry : chunkIndex.entrySet()) {
            if (buffer.remaining() < INDEX_ENTRY_SIZE) {
                payloadOffset = flushCheckpointBuffer(buffer, crc, payloadOffset);
            }
            ChunkKey key = entry.getKey();
            IndexEntry value = entry.getValue();
            buffer.putInt(key.x()).putInt(key.z()).putLong(value.offset())
                    .putLong(value.length()).putLong(value.generation());
        }
        payloadOffset = flushCheckpointBuffer(buffer, crc, payloadOffset);
        if (payloadOffset != offset + RECORD_HEADER_SIZE + payloadLength) {
            throw new IOException("Index changed while its checkpoint was being written");
        }

        RecordHeader finalHeader = new RecordHeader(TYPE_INDEX, (byte) 0, (byte) 0,
                INDEX_VERSION, 0, 0, 0, targetGeneration, payloadLength, payloadLength,
                (int) crc.getValue());
        writeFully(channel, encodeRecordHeader(finalHeader), offset);
        long totalLength = finalHeader.totalLength();
        writePadding(offset + RECORD_HEADER_SIZE + payloadLength,
                totalLength - RECORD_HEADER_SIZE - payloadLength);
        return totalLength;
    }

    private long flushCheckpointBuffer(ByteBuffer buffer, CRC32C crc, long offset) throws IOException {
        buffer.flip();
        if (!buffer.hasRemaining()) {
            buffer.clear();
            return offset;
        }
        ByteBuffer checksumView = buffer.asReadOnlyBuffer();
        crc.update(checksumView);
        int length = buffer.remaining();
        writeFully(channel, buffer, offset);
        buffer.clear();
        return offset + length;
    }

    private long writeRecord(long offset, RecordHeader header, byte[] storedPayload) throws IOException {
        if (storedPayload.length != header.storedLength()) {
            throw new IllegalArgumentException("Stored payload length does not match record header");
        }
        writeFully(channel, encodeRecordHeader(header), offset);
        if (storedPayload.length != 0) {
            writeFully(channel, ByteBuffer.wrap(storedPayload), offset + RECORD_HEADER_SIZE);
        }
        long totalLength = header.totalLength();
        writePadding(offset + RECORD_HEADER_SIZE + storedPayload.length,
                totalLength - RECORD_HEADER_SIZE - storedPayload.length);
        return totalLength;
    }

    private void writePadding(long offset, long length) throws IOException {
        if (length == 0) return;
        writeFully(channel, ByteBuffer.allocate(Math.toIntExact(length)), offset);
    }

    private void publish(long newGeneration, long newCommittedLength,
                         long newCheckpointOffset, long newCheckpointLength,
                         long newWorldDataOffset, long newWorldDataLength) throws IOException {
        forceRecords();
        int targetSuperblock = 1 - activeSuperblock;
        Superblock superblock = new Superblock(newGeneration, newCommittedLength,
                newCheckpointOffset, newCheckpointLength, newWorldDataOffset, newWorldDataLength,
                fileId);
        try {
            writeFully(channel, encodeSuperblock(superblock), targetSuperblock * (long) SUPERBLOCK_SIZE);
            forceSuperblock();
        } catch (IOException exception) {
            poison(exception);
            throw exception;
        } catch (RuntimeException | Error exception) {
            poison(exception);
            throw exception;
        }

        generation = newGeneration;
        committedLength = newCommittedLength;
        checkpointOffset = newCheckpointOffset;
        checkpointLength = newCheckpointLength;
        worldDataOffset = newWorldDataOffset;
        worldDataLength = newWorldDataLength;
        activeSuperblock = targetSuperblock;
    }

    private void forceRecords() throws IOException {
        switch (options.syncMode()) {
            case FULL -> channel.force(true);
            case DATA -> channel.force(false);
            case NONE -> {
            }
        }
    }

    private void forceSuperblock() throws IOException {
        switch (options.syncMode()) {
            case FULL -> channel.force(true);
            case DATA -> channel.force(false);
            case NONE -> {
            }
        }
    }

    private void truncateAfterFailure(long oldLength, Throwable primary) {
        try {
            channel.truncate(oldLength);
        } catch (IOException truncateFailure) {
            primary.addSuppressed(truncateFailure);
        }
    }

    private void poison(Throwable primary) {
        closed = true;
        try {
            processLock.close();
        } catch (IOException closeFailure) {
            primary.addSuppressed(closeFailure);
        }
        try {
            channel.close();
        } catch (IOException closeFailure) {
            primary.addSuppressed(closeFailure);
        }
    }

    private void rollback(List<RollbackEntry> rollback) {
        for (int index = rollback.size() - 1; index >= 0; index--) {
            RollbackEntry entry = rollback.get(index);
            if (entry.previous() == null) chunkIndex.remove(entry.key());
            else chunkIndex.put(entry.key(), entry.previous());
        }
    }

    private RecordHeader readRecordHeader(long offset) throws IOException {
        if (offset < DATA_START || offset > committedLength - RECORD_HEADER_SIZE) {
            throw new ArcticFormatException("Record header offset is outside the committed file: " + offset);
        }
        ByteBuffer buffer = allocate(RECORD_HEADER_SIZE);
        readFully(channel, buffer, offset);
        byte[] bytes = buffer.array();
        int storedChecksum = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).getInt(56);
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).putInt(56, 0);
        if (checksum(bytes) != storedChecksum) {
            throw new ArcticFormatException("Record header checksum mismatch at " + offset);
        }
        buffer.position(0);
        if (buffer.getInt() != RECORD_MAGIC || Short.toUnsignedInt(buffer.getShort()) != RECORD_HEADER_SIZE) {
            throw new ArcticFormatException("Invalid record header at " + offset);
        }
        byte type = buffer.get();
        byte encoding = buffer.get();
        byte compression = buffer.get();
        buffer.get(); // flags
        buffer.getShort();
        int schemaVersion = buffer.getInt();
        int dataVersion = buffer.getInt();
        int x = buffer.getInt();
        int z = buffer.getInt();
        long recordGeneration = buffer.getLong();
        long uncompressedLength = buffer.getLong();
        long storedLength = buffer.getLong();
        int payloadChecksum = buffer.getInt();
        buffer.getInt(); // header checksum
        buffer.getLong(); // timestamp/reserved
        buffer.position(RECORD_HEADER_SIZE);

        RecordHeader header = new RecordHeader(type, encoding, compression, schemaVersion,
                dataVersion, x, z, recordGeneration, uncompressedLength, storedLength,
                payloadChecksum);
        long end;
        try {
            end = Math.addExact(offset, header.totalLength());
        } catch (ArithmeticException exception) {
            throw new ArcticFormatException("Record length overflow at " + offset, exception);
        }
        if (schemaVersion < 1 || recordGeneration < 1 || uncompressedLength < 0
                || storedLength < 0 || end > committedLength) {
            throw new ArcticFormatException("Invalid record bounds at " + offset);
        }
        return header;
    }

    private static ByteBuffer encodeRecordHeader(RecordHeader header) {
        ByteBuffer buffer = allocate(RECORD_HEADER_SIZE);
        buffer.putInt(RECORD_MAGIC).putShort((short) RECORD_HEADER_SIZE)
                .put(header.type()).put(header.encoding()).put(header.compression()).put((byte) 0)
                .putShort((short) 0).putInt(header.schemaVersion()).putInt(header.dataVersion())
                .putInt(header.x()).putInt(header.z()).putLong(header.generation())
                .putLong(header.uncompressedLength()).putLong(header.storedLength())
                .putInt(header.payloadChecksum()).putInt(0).putLong(0L);
        while (buffer.position() < RECORD_HEADER_SIZE) buffer.put((byte) 0);
        byte[] bytes = buffer.array();
        buffer.putInt(56, checksum(bytes));
        buffer.position(0);
        return buffer;
    }

    private static void initialize(FileChannel channel, UUID fileId, ArcticSyncMode syncMode) throws IOException {
        channel.truncate(0);
        Superblock first = new Superblock(1, DATA_START, 0, 0, 0, 0, fileId);
        Superblock second = new Superblock(0, DATA_START, 0, 0, 0, 0, fileId);
        writeFully(channel, encodeSuperblock(first), 0);
        writeFully(channel, encodeSuperblock(second), SUPERBLOCK_SIZE);
        switch (syncMode) {
            case FULL -> channel.force(true);
            case DATA -> channel.force(false);
            case NONE -> {
            }
        }
    }

    private static Superblock readSuperblock(FileChannel channel, int slot) throws IOException {
        ByteBuffer buffer = allocate(SUPERBLOCK_SIZE);
        readFully(channel, buffer, slot * (long) SUPERBLOCK_SIZE);
        byte[] bytes = buffer.array();
        int storedCrc = buffer.getInt(SUPERBLOCK_CRC_OFFSET);
        buffer.putInt(SUPERBLOCK_CRC_OFFSET, 0);
        if (checksum(bytes) != storedCrc) return null;
        buffer.position(0);
        byte[] magic = new byte[FILE_MAGIC.length];
        buffer.get(magic);
        if (!Arrays.equals(magic, FILE_MAGIC)) return null;
        if (buffer.getInt() != CONTAINER_VERSION || buffer.getInt() != SUPERBLOCK_SIZE) return null;
        long generation = buffer.getLong();
        long committedLength = buffer.getLong();
        long checkpointOffset = buffer.getLong();
        long checkpointLength = buffer.getLong();
        long worldOffset = buffer.getLong();
        long worldLength = buffer.getLong();
        UUID fileId = new UUID(buffer.getLong(), buffer.getLong());
        buffer.getLong(); // feature flags
        if (generation < 0 || committedLength < DATA_START || checkpointOffset < 0
                || checkpointLength < 0 || worldOffset < 0 || worldLength < 0) return null;
        return new Superblock(generation, committedLength, checkpointOffset, checkpointLength,
                worldOffset, worldLength, fileId);
    }

    private static ByteBuffer encodeSuperblock(Superblock superblock) {
        ByteBuffer buffer = allocate(SUPERBLOCK_SIZE);
        buffer.put(FILE_MAGIC).putInt(CONTAINER_VERSION).putInt(SUPERBLOCK_SIZE)
                .putLong(superblock.generation()).putLong(superblock.committedLength())
                .putLong(superblock.checkpointOffset()).putLong(superblock.checkpointLength())
                .putLong(superblock.worldDataOffset()).putLong(superblock.worldDataLength())
                .putLong(superblock.fileId().getMostSignificantBits())
                .putLong(superblock.fileId().getLeastSignificantBits()).putLong(0L);
        buffer.putInt(SUPERBLOCK_CRC_OFFSET, 0);
        buffer.putInt(SUPERBLOCK_CRC_OFFSET, checksum(buffer.array()));
        buffer.position(0);
        return buffer;
    }

    private static int checksum(byte[] bytes) {
        CRC32C crc = new CRC32C();
        updateChecksum(crc, bytes);
        return (int) crc.getValue();
    }

    private static void updateChecksum(CRC32C crc, byte[] bytes) {
        crc.update(bytes, 0, bytes.length);
    }

    private static ByteBuffer allocate(int length) {
        return ByteBuffer.allocate(length).order(ByteOrder.BIG_ENDIAN);
    }

    private static void readFully(FileChannel channel, ByteBuffer target, long offset) throws IOException {
        target.clear();
        long position = offset;
        while (target.hasRemaining()) {
            int read = channel.read(target, position);
            if (read < 0) throw new EOFException("Unexpected end of Arctic file at " + position);
            if (read == 0) continue;
            position += read;
        }
    }

    private static void writeFully(FileChannel channel, ByteBuffer source, long offset) throws IOException {
        long position = offset;
        while (source.hasRemaining()) {
            int written = channel.write(source, position);
            if (written == 0) continue;
            position += written;
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("Arctic file is closed");
    }

    private void ensureOpenUnchecked() {
        if (closed) throw new IllegalStateException("Arctic file is closed");
    }

    private static long align8(long value) {
        return Math.addExact(value, 7L) & ~7L;
    }

    private record Superblock(long generation, long committedLength,
                              long checkpointOffset, long checkpointLength,
                              long worldDataOffset, long worldDataLength, UUID fileId) {
        private boolean isPlausible(long physicalLength) {
            return generation >= 0 && committedLength >= DATA_START && committedLength <= physicalLength
                    && validPointer(checkpointOffset, checkpointLength, committedLength)
                    && validPointer(worldDataOffset, worldDataLength, committedLength);
        }

        private static boolean validPointer(long offset, long length, long boundary) {
            if (offset == 0 || length == 0) return offset == 0 && length == 0;
            return offset >= DATA_START && length >= RECORD_HEADER_SIZE
                    && length <= boundary - DATA_START && offset <= boundary - length;
        }
    }

    private record SuperblockCandidate(Superblock superblock, int slot) {
    }

    private record ChunkKey(int x, int z) {
    }

    private record IndexEntry(long offset, long length, long generation) {
    }

    private record RollbackEntry(ChunkKey key, IndexEntry previous) {
    }

    private record PreparedPayload(ArcticCompression compression, byte[] bytes) {
    }

    private record PreparedRecord(byte type, byte encoding, byte compression, int schemaVersion,
                                  int dataVersion, int x, int z, long uncompressedLength,
                                  byte[] storedPayload) {
        private RecordHeader header(long generation) {
            return new RecordHeader(type, encoding, compression, schemaVersion, dataVersion, x, z,
                    generation, uncompressedLength, storedPayload.length, checksum(storedPayload));
        }
    }

    private record RecordHeader(byte type, byte encoding, byte compression, int schemaVersion,
                                int dataVersion, int x, int z, long generation,
                                long uncompressedLength, long storedLength, int payloadChecksum) {
        private long totalLength() {
            return align8(Math.addExact(RECORD_HEADER_SIZE, storedLength));
        }
    }
}
