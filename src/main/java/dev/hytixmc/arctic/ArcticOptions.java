package dev.hytixmc.arctic;

import java.util.Objects;

/** Limits and write defaults for an Arctic world file. */
public record ArcticOptions(
        ArcticChunkEncoding chunkEncoding,
        ArcticCompression compression,
        int compressionLevel,
        ArcticSyncMode syncMode,
        int checkpointInterval,
        int saveBatchChunkLimit,
        int saveBatchByteLimit,
        int maxCompressedChunkBytes,
        int maxUncompressedChunkBytes,
        int maxWorldDataBytes) {

    public static final int DEFAULT_SAVE_BATCH_BYTE_LIMIT = 64 * 1024 * 1024;
    public static final int DEFAULT_MAX_COMPRESSED_CHUNK_BYTES = 32 * 1024 * 1024;
    public static final int DEFAULT_MAX_UNCOMPRESSED_CHUNK_BYTES = 128 * 1024 * 1024;
    public static final int DEFAULT_MAX_WORLD_DATA_BYTES = 16 * 1024 * 1024;

    public ArcticOptions {
        Objects.requireNonNull(chunkEncoding, "chunkEncoding");
        Objects.requireNonNull(compression, "compression");
        Objects.requireNonNull(syncMode, "syncMode");
        if (compressionLevel < -5 || compressionLevel > 22) {
            throw new IllegalArgumentException("compressionLevel must be between -5 and 22");
        }
        if (checkpointInterval < 1) {
            throw new IllegalArgumentException("checkpointInterval must be positive");
        }
        if (saveBatchChunkLimit < 1 || saveBatchByteLimit < 1) {
            throw new IllegalArgumentException("save batch limits must be positive");
        }
        if (maxCompressedChunkBytes < 1 || maxUncompressedChunkBytes < 1 || maxWorldDataBytes < 1) {
            throw new IllegalArgumentException("size limits must be positive");
        }
        if (maxCompressedChunkBytes > maxUncompressedChunkBytes) {
            throw new IllegalArgumentException("maxCompressedChunkBytes cannot exceed maxUncompressedChunkBytes");
        }
    }

    public static ArcticOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private ArcticChunkEncoding chunkEncoding = ArcticChunkEncoding.BINARY;
        private ArcticCompression compression = ArcticCompression.ZSTD;
        private int compressionLevel = 3;
        private ArcticSyncMode syncMode = ArcticSyncMode.FULL;
        private int checkpointInterval = 4096;
        private int saveBatchChunkLimit = 64;
        private int saveBatchByteLimit = DEFAULT_SAVE_BATCH_BYTE_LIMIT;
        private int maxCompressedChunkBytes = DEFAULT_MAX_COMPRESSED_CHUNK_BYTES;
        private int maxUncompressedChunkBytes = DEFAULT_MAX_UNCOMPRESSED_CHUNK_BYTES;
        private int maxWorldDataBytes = DEFAULT_MAX_WORLD_DATA_BYTES;

        private Builder() {
        }

        public Builder chunkEncoding(ArcticChunkEncoding chunkEncoding) {
            this.chunkEncoding = Objects.requireNonNull(chunkEncoding, "chunkEncoding");
            return this;
        }

        public Builder compression(ArcticCompression compression) {
            this.compression = Objects.requireNonNull(compression, "compression");
            return this;
        }

        public Builder compressionLevel(int compressionLevel) {
            this.compressionLevel = compressionLevel;
            return this;
        }

        public Builder syncMode(ArcticSyncMode syncMode) {
            this.syncMode = Objects.requireNonNull(syncMode, "syncMode");
            return this;
        }

        public Builder checkpointInterval(int checkpointInterval) {
            this.checkpointInterval = checkpointInterval;
            return this;
        }

        public Builder saveBatchChunkLimit(int chunks) {
            this.saveBatchChunkLimit = chunks;
            return this;
        }

        public Builder saveBatchByteLimit(int bytes) {
            this.saveBatchByteLimit = bytes;
            return this;
        }

        public Builder maxCompressedChunkBytes(int bytes) {
            this.maxCompressedChunkBytes = bytes;
            return this;
        }

        public Builder maxUncompressedChunkBytes(int bytes) {
            this.maxUncompressedChunkBytes = bytes;
            return this;
        }

        public Builder maxWorldDataBytes(int bytes) {
            this.maxWorldDataBytes = bytes;
            return this;
        }

        public ArcticOptions build() {
            return new ArcticOptions(chunkEncoding, compression, compressionLevel, syncMode,
                    checkpointInterval, saveBatchChunkLimit, saveBatchByteLimit,
                    maxCompressedChunkBytes, maxUncompressedChunkBytes, maxWorldDataBytes);
        }
    }
}
