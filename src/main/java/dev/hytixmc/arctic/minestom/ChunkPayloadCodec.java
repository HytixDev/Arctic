package dev.hytixmc.arctic.minestom;

import dev.hytixmc.arctic.ArcticChunkEncoding;
import dev.hytixmc.arctic.ArcticFormatException;

import java.io.IOException;

interface ChunkPayloadCodec {
    byte[] encode(ChunkModel model) throws IOException;

    ChunkModel decode(byte[] payload) throws IOException;

    static ChunkPayloadCodec forEncoding(ArcticChunkEncoding encoding) throws ArcticFormatException {
        return switch (encoding) {
            case BINARY -> BinaryChunkCodec.INSTANCE;
            case NBT -> NbtChunkCodec.INSTANCE;
        };
    }
}
