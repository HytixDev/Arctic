package dev.hytixmc.arctic.format;

import dev.hytixmc.arctic.ArcticChunkEncoding;

import java.util.Arrays;
import java.util.Objects;

/** A decoded chunk payload returned by the low-level streamed container. */
public record StoredChunk(int x, int z, ArcticChunkEncoding encoding, int schemaVersion,
                          int dataVersion, byte[] payload) {
    public StoredChunk {
        Objects.requireNonNull(encoding, "encoding");
        payload = Arrays.copyOf(Objects.requireNonNull(payload, "payload"), payload.length);
        if (schemaVersion < 1) throw new IllegalArgumentException("schemaVersion must be positive");
    }

    public int payloadLength() {
        return payload.length;
    }

    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }
}
