package dev.hytixmc.arctic.format;

import dev.hytixmc.arctic.ArcticChunkEncoding;
import dev.hytixmc.arctic.ArcticCompression;
import dev.hytixmc.arctic.ArcticFormatException;
import dev.hytixmc.arctic.ArcticOptions;
import dev.hytixmc.arctic.ArcticSyncMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArcticFileTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsBatchesMetadataUpdatesAndTombstones() throws IOException {
        Path path = temporaryDirectory.resolve("world.arctic");
        ArcticOptions options = ArcticOptions.builder().checkpointInterval(2).build();
        byte[] first = "first chunk payload".repeat(100).getBytes();
        byte[] replacement = "replacement".repeat(80).getBytes();
        byte[] second = {1, 2, 3, 4, 5};
        byte[] metadata = "world metadata".getBytes();

        try (ArcticFile file = ArcticFile.open(path, options)) {
            assertEquals(0, file.chunkCount());
            file.writeWorldData(metadata);
            file.writeChunks(List.of(
                    new StoredChunk(-20, 31, ArcticChunkEncoding.BINARY, 1, 4444, first),
                    new StoredChunk(Integer.MAX_VALUE, Integer.MIN_VALUE, ArcticChunkEncoding.NBT,
                            1, 4444, second)));
            assertEquals(2, file.chunkCount());
            assertArrayEquals(first, file.readChunk(-20, 31).orElseThrow().payload());
            assertArrayEquals(second, file.readChunk(Integer.MAX_VALUE, Integer.MIN_VALUE)
                    .orElseThrow().payload());
            file.writeChunks(List.of(new StoredChunk(-20, 31, ArcticChunkEncoding.NBT,
                    1, 5555, replacement)));
            assertTrue(file.deleteChunk(Integer.MAX_VALUE, Integer.MIN_VALUE));
            assertFalse(file.deleteChunk(123, 456));
        }

        try (ArcticFile reopened = ArcticFile.open(path, options)) {
            assertEquals(1, reopened.chunkCount());
            assertArrayEquals(metadata, reopened.readWorldData());
            StoredChunk chunk = reopened.readChunk(-20, 31).orElseThrow();
            assertEquals(ArcticChunkEncoding.NBT, chunk.encoding());
            assertEquals(5555, chunk.dataVersion());
            assertArrayEquals(replacement, chunk.payload());
            assertTrue(reopened.readChunk(Integer.MAX_VALUE, Integer.MIN_VALUE).isEmpty());
        }
    }

    @Test
    void ignoresAndTruncatesUncommittedTail() throws IOException {
        Path path = temporaryDirectory.resolve("tail.arctic");
        ArcticOptions options = ArcticOptions.defaults();
        try (ArcticFile file = ArcticFile.open(path, options)) {
            file.writeChunks(List.of(chunk(1, 2, "committed")));
        }
        long committedLength = Files.size(path);
        Files.write(path, new byte[127], StandardOpenOption.APPEND);
        assertEquals(committedLength + 127, Files.size(path));

        try (ArcticFile reopened = ArcticFile.open(path, options)) {
            assertArrayEquals("committed".getBytes(), reopened.readChunk(1, 2).orElseThrow().payload());
            assertEquals(committedLength, Files.size(path));
        }
    }

    @Test
    void detectsChunkCorruptionWithoutLosingOtherChunks() throws IOException {
        Path path = temporaryDirectory.resolve("corrupt.arctic");
        ArcticOptions options = ArcticOptions.builder()
                .compression(ArcticCompression.NONE)
                .syncMode(ArcticSyncMode.DATA)
                .checkpointInterval(100)
                .build();
        try (ArcticFile file = ArcticFile.open(path, options)) {
            file.writeChunks(List.of(chunk(0, 0, "one"), chunk(1, 0, "two")));
        }

        byte[] fileBytes = Files.readAllBytes(path);
        int firstPayloadOffset = 8192 + 80;
        fileBytes[firstPayloadOffset] ^= 0x55;
        Files.write(path, fileBytes);

        try (ArcticFile reopened = ArcticFile.open(path, options)) {
            assertThrows(ArcticFormatException.class, () -> reopened.readChunk(0, 0));
            assertArrayEquals("two".getBytes(), reopened.readChunk(1, 0).orElseThrow().payload());
        }
    }

    @Test
    void rejectsASecondWriterAndCompactsLiveData() throws IOException {
        Path source = temporaryDirectory.resolve("source.arctic");
        Path compacted = temporaryDirectory.resolve("compacted.arctic");
        ArcticOptions options = ArcticOptions.defaults();
        try (ArcticFile file = ArcticFile.open(source, options)) {
            assertThrows(IOException.class, () -> ArcticFile.open(source, options));
            for (int revision = 0; revision < 100; revision++) {
                file.writeChunks(List.of(chunk(0, 0, "old-" + revision)));
            }
            file.writeChunks(List.of(chunk(0, 0, "new"), chunk(5, -8, "other")));
            long sourceLength = Files.size(source);
            file.compactTo(compacted);
            assertTrue(Files.size(compacted) < sourceLength);
        }

        try (ArcticFile file = ArcticFile.open(compacted, options)) {
            assertEquals(2, file.chunkCount());
            assertArrayEquals("new".getBytes(), file.readChunk(0, 0).orElseThrow().payload());
            assertArrayEquals("other".getBytes(), file.readChunk(5, -8).orElseThrow().payload());
        }
    }

    @Test
    void fallsBackToOlderPhysicallyUsableSuperblock() throws IOException {
        Path path = temporaryDirectory.resolve("fallback.arctic");
        ArcticOptions options = ArcticOptions.defaults();
        try (ArcticFile file = ArcticFile.open(path, options)) {
            file.writeChunks(List.of(chunk(9, 9, "new-generation")));
        }

        byte[] bytes = Files.readAllBytes(path);
        ByteBuffer second = ByteBuffer.wrap(bytes, 4096, 4096).slice().order(ByteOrder.BIG_ENDIAN);
        second.putLong(24, bytes.length + 4096L);
        second.putInt(4092, 0);
        CRC32C checksum = new CRC32C();
        checksum.update(second.array(), second.arrayOffset(), 4096);
        second.putInt(4092, (int) checksum.getValue());
        Files.write(path, bytes);

        try (ArcticFile recovered = ArcticFile.open(path, options)) {
            assertEquals(1, recovered.generation());
            assertTrue(recovered.readChunk(9, 9).isEmpty());
            assertEquals(8192, Files.size(path));
        }
    }

    @Test
    void fallsBackWhenNewestCheckpointIsCorrupt() throws IOException {
        Path path = temporaryDirectory.resolve("checkpoint-fallback.arctic");
        ArcticOptions options = ArcticOptions.builder().checkpointInterval(1).build();
        try (ArcticFile file = ArcticFile.open(path, options)) {
            file.writeChunks(List.of(chunk(4, 7, "checkpointed")));
        }

        byte[] bytes = Files.readAllBytes(path);
        ByteBuffer second = ByteBuffer.wrap(bytes, 4096, 4096).slice().order(ByteOrder.BIG_ENDIAN);
        long checkpointOffset = second.getLong(32);
        bytes[Math.toIntExact(checkpointOffset + 80)] ^= 1;
        Files.write(path, bytes);

        try (ArcticFile recovered = ArcticFile.open(path, options)) {
            assertEquals(1, recovered.generation());
            assertTrue(recovered.readChunk(4, 7).isEmpty());
        }
    }

    @Test
    void storedChunkDefensivelyCopiesPayload() {
        byte[] payload = {1, 2, 3};
        StoredChunk chunk = new StoredChunk(0, 0, ArcticChunkEncoding.BINARY, 1, 1, payload);
        payload[0] = 9;
        assertArrayEquals(new byte[]{1, 2, 3}, chunk.payload());
        byte[] returned = chunk.payload();
        returned[1] = 9;
        assertArrayEquals(new byte[]{1, 2, 3}, chunk.payload());
    }

    private static StoredChunk chunk(int x, int z, String payload) {
        return new StoredChunk(x, z, ArcticChunkEncoding.BINARY, 1, 4000,
                payload.getBytes());
    }
}
