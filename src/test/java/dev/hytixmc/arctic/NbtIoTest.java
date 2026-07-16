package dev.hytixmc.arctic;

import dev.hytixmc.arctic.minestom.NbtIo;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertThrows;

class NbtIoTest {
    @Test
    void rejectsTrailingData() throws IOException {
        byte[] valid = NbtIo.write(CompoundBinaryTag.builder().putInt("value", 1).build());
        byte[] trailing = Arrays.copyOf(valid, valid.length + 1);
        assertThrows(IOException.class, () -> NbtIo.read(trailing));
    }

    @Test
    void rejectsHugeDeclaredAllocationFromTinyInput() {
        ByteBuffer malicious = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN);
        malicious.put((byte) 10).putShort((short) 0); // Named root compound.
        malicious.put((byte) 7).putShort((short) 1).put((byte) 'x'); // Byte-array child.
        malicious.putInt(Integer.MAX_VALUE);
        malicious.put((byte) 0); // End compound, unreachable for a valid byte array.
        assertThrows(IOException.class, () -> NbtIo.read(malicious.array()));
    }
}
