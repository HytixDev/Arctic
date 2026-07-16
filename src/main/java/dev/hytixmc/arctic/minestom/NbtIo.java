package dev.hytixmc.arctic.minestom;

import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.CompoundBinaryTag;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

public final class NbtIo {
    private NbtIo() {
    }

    public static byte[] write(CompoundBinaryTag tag) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BinaryTagIO.writer().writeNamed(Map.entry("", tag), output, BinaryTagIO.Compression.NONE);
        return output.toByteArray();
    }

    public static CompoundBinaryTag read(byte[] bytes) throws IOException {
        NbtValidator.validate(bytes);
        long estimatedLimit = Math.max(1024L * 1024L,
                Math.min(256L * 1024L * 1024L, Math.multiplyExact(bytes.length, 8L)));
        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
            return BinaryTagIO.reader(estimatedLimit).read(input, BinaryTagIO.Compression.NONE);
        }
    }
}
