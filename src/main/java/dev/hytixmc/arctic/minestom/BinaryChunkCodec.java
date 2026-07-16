package dev.hytixmc.arctic.minestom;

import dev.hytixmc.arctic.ArcticFormatException;
import net.kyori.adventure.nbt.CompoundBinaryTag;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class BinaryChunkCodec implements ChunkPayloadCodec {
    static final BinaryChunkCodec INSTANCE = new BinaryChunkCodec();

    private static final int MAGIC = 0x41434231; // ACB1
    private static final int MAX_SECTIONS = 4096;
    private static final int MAX_BLOCK_ENTITIES = 65_536;
    private static final int MAX_STRING_BYTES = 32_767;
    private static final int MAX_NBT_BYTES = 64 * 1024 * 1024;
    private static final int BLOCK_COUNT = 4096;
    private static final int BIOME_COUNT = 64;

    private BinaryChunkCodec() {
    }

    @Override
    public byte[] encode(ChunkModel model) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(model.x());
            output.writeInt(model.z());
            output.writeInt(model.minSection());
            output.writeInt(model.maxSection());
            writeVarInt(output, model.sections().size());
            for (SectionModel section : model.sections()) writeSection(output, section);

            writeVarInt(output, model.blockEntities().size());
            for (BlockEntityModel blockEntity : model.blockEntities()) {
                output.writeByte(blockEntity.x());
                output.writeInt(blockEntity.y());
                output.writeByte(blockEntity.z());
                output.writeBoolean(blockEntity.id() != null);
                if (blockEntity.id() != null) writeString(output, blockEntity.id());
                output.writeBoolean(blockEntity.data() != null);
                if (blockEntity.data() != null) writeBytes(output, NbtIo.write(blockEntity.data()));
            }
            writeLongArray(output, model.motionBlockingHeightmap());
            writeLongArray(output, model.worldSurfaceHeightmap());
            writeBytes(output, NbtIo.write(model.userData()));
        }
        return bytes.toByteArray();
    }

    @Override
    public ChunkModel decode(byte[] payload) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != MAGIC) throw new ArcticFormatException("Invalid binary chunk magic");
            int x = input.readInt();
            int z = input.readInt();
            int minSection = input.readInt();
            int maxSection = input.readInt();
            if (maxSection <= minSection || (long) maxSection - minSection > MAX_SECTIONS) {
                throw new ArcticFormatException("Invalid chunk section range");
            }
            int sectionCount = readLength(input, MAX_SECTIONS, "section count");
            List<SectionModel> sections = new ArrayList<>(sectionCount);
            for (int index = 0; index < sectionCount; index++) sections.add(readSection(input));

            int blockEntityCount = readLength(input, MAX_BLOCK_ENTITIES, "block entity count");
            List<BlockEntityModel> blockEntities = new ArrayList<>(blockEntityCount);
            for (int index = 0; index < blockEntityCount; index++) {
                int localX = Byte.toUnsignedInt(input.readByte());
                int y = input.readInt();
                int localZ = Byte.toUnsignedInt(input.readByte());
                String id = input.readBoolean() ? readString(input) : null;
                CompoundBinaryTag data = input.readBoolean() ? NbtIo.read(readBytes(input, MAX_NBT_BYTES)) : null;
                blockEntities.add(new BlockEntityModel(localX, y, localZ, id, data));
            }
            long[] motionBlocking = readLongArray(input, 1024);
            long[] worldSurface = readLongArray(input, 1024);
            CompoundBinaryTag userData = NbtIo.read(readBytes(input, MAX_NBT_BYTES));
            if (input.available() != 0) throw new ArcticFormatException("Trailing binary chunk data");
            return new ChunkModel(x, z, minSection, maxSection, List.copyOf(sections),
                    List.copyOf(blockEntities), motionBlocking, worldSurface, userData);
        } catch (EOFException exception) {
            throw new ArcticFormatException("Truncated binary chunk payload", exception);
        } catch (IllegalArgumentException exception) {
            throw new ArcticFormatException("Malformed binary chunk payload", exception);
        }
    }

    private static void writeSection(DataOutputStream output, SectionModel section) throws IOException {
        output.writeInt(section.y());
        writePalette(output, section.blockPalette(), section.blockIndices(), BLOCK_COUNT);
        writePalette(output, section.biomePalette(), section.biomeIndices(), BIOME_COUNT);
        writeLight(output, section.blockLight());
        writeLight(output, section.skyLight());
    }

    private static SectionModel readSection(DataInputStream input) throws IOException {
        int y = input.readInt();
        DecodedPalette blocks = readPalette(input, BLOCK_COUNT, "block");
        DecodedPalette biomes = readPalette(input, BIOME_COUNT, "biome");
        byte[] blockLight = readLight(input);
        byte[] skyLight = readLight(input);
        return new SectionModel(y, blocks.palette(), blocks.indices(), biomes.palette(),
                biomes.indices(), blockLight, skyLight);
    }

    private static void writePalette(DataOutputStream output, String[] palette, int[] indices,
                                     int valueCount) throws IOException {
        writeVarInt(output, palette.length);
        for (String value : palette) writeString(output, value);
        if (palette.length <= 1) return;
        if (indices.length != valueCount) throw new IOException("Palette has the wrong index count");
        int bits = bitsToRepresent(palette.length - 1);
        output.writeByte(bits);
        long[] packed = pack(indices, bits);
        writeVarInt(output, packed.length);
        for (long value : packed) output.writeLong(value);
    }

    private static DecodedPalette readPalette(DataInputStream input, int valueCount, String name)
            throws IOException {
        int paletteSize = readLength(input, valueCount, name + " palette size");
        if (paletteSize == 0) throw new ArcticFormatException("Empty " + name + " palette");
        String[] palette = new String[paletteSize];
        for (int index = 0; index < palette.length; index++) palette[index] = readString(input);
        if (paletteSize == 1) return new DecodedPalette(palette, new int[0]);

        int bits = Byte.toUnsignedInt(input.readByte());
        int expectedBits = bitsToRepresent(paletteSize - 1);
        if (bits != expectedBits) throw new ArcticFormatException("Invalid " + name + " palette bit width");
        int longCount = readLength(input, valueCount, name + " packed data length");
        int expectedLongs = packedLength(valueCount, bits);
        if (longCount != expectedLongs) throw new ArcticFormatException("Invalid " + name + " packed data length");
        long[] packed = new long[longCount];
        for (int index = 0; index < longCount; index++) packed[index] = input.readLong();
        int[] indices = unpack(packed, bits, valueCount);
        for (int index : indices) {
            if (index >= paletteSize) throw new ArcticFormatException("Out-of-range " + name + " palette index");
        }
        return new DecodedPalette(palette, indices);
    }

    private static void writeLight(DataOutputStream output, byte[] light) throws IOException {
        if (light.length == 0) {
            output.writeByte(0);
        } else if (all(light, (byte) 0)) {
            output.writeByte(1);
        } else if (all(light, (byte) 0xFF)) {
            output.writeByte(2);
        } else {
            if (light.length != 2048) throw new IOException("Invalid light array length");
            output.writeByte(3);
            output.write(light);
        }
    }

    private static byte[] readLight(DataInputStream input) throws IOException {
        return switch (Byte.toUnsignedInt(input.readByte())) {
            case 0 -> new byte[0];
            case 1 -> new byte[2048];
            case 2 -> {
                byte[] light = new byte[2048];
                Arrays.fill(light, (byte) 0xFF);
                yield light;
            }
            case 3 -> {
                byte[] light = input.readNBytes(2048);
                if (light.length != 2048) throw new EOFException();
                yield light;
            }
            default -> throw new ArcticFormatException("Invalid light content marker");
        };
    }

    private static boolean all(byte[] values, byte expected) {
        if (values.length != 2048) return false;
        for (byte value : values) if (value != expected) return false;
        return true;
    }

    private static long[] pack(int[] values, int bits) {
        int valuesPerLong = 64 / bits;
        long mask = (1L << bits) - 1L;
        long[] packed = new long[packedLength(values.length, bits)];
        for (int index = 0; index < values.length; index++) {
            int longIndex = index / valuesPerLong;
            int bitIndex = (index % valuesPerLong) * bits;
            packed[longIndex] |= ((long) values[index] & mask) << bitIndex;
        }
        return packed;
    }

    private static int[] unpack(long[] packed, int bits, int size) {
        int valuesPerLong = 64 / bits;
        long mask = (1L << bits) - 1L;
        int[] values = new int[size];
        for (int index = 0; index < size; index++) {
            int longIndex = index / valuesPerLong;
            int bitIndex = (index % valuesPerLong) * bits;
            values[index] = (int) (packed[longIndex] >>> bitIndex & mask);
        }
        return values;
    }

    private static int packedLength(int valueCount, int bits) {
        int valuesPerLong = 64 / bits;
        return (valueCount + valuesPerLong - 1) / valuesPerLong;
    }

    private static int bitsToRepresent(int maxValue) {
        return Math.max(1, Integer.SIZE - Integer.numberOfLeadingZeros(maxValue));
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) throw new IOException("String is too long");
        writeVarInt(output, bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        return new String(readBytes(input, MAX_STRING_BYTES), StandardCharsets.UTF_8);
    }

    private static void writeBytes(DataOutputStream output, byte[] value) throws IOException {
        writeVarInt(output, value.length);
        output.write(value);
    }

    private static byte[] readBytes(DataInputStream input, int maximum) throws IOException {
        int length = readLength(input, maximum, "byte array length");
        byte[] result = input.readNBytes(length);
        if (result.length != length) throw new EOFException();
        return result;
    }

    private static void writeLongArray(DataOutputStream output, long[] values) throws IOException {
        writeVarInt(output, values.length);
        for (long value : values) output.writeLong(value);
    }

    private static long[] readLongArray(DataInputStream input, int maximum) throws IOException {
        int length = readLength(input, maximum, "long array length");
        long[] result = new long[length];
        for (int index = 0; index < length; index++) result[index] = input.readLong();
        return result;
    }

    private static void writeVarInt(DataOutputStream output, int value) throws IOException {
        if (value < 0) throw new IllegalArgumentException("VarInt cannot be negative");
        while ((value & ~0x7F) != 0) {
            output.writeByte(value & 0x7F | 0x80);
            value >>>= 7;
        }
        output.writeByte(value);
    }

    private static int readLength(DataInputStream input, int maximum, String name) throws IOException {
        int value = readVarInt(input);
        if (value < 0 || value > maximum) throw new ArcticFormatException("Invalid " + name + ": " + value);
        return value;
    }

    private static int readVarInt(DataInputStream input) throws IOException {
        int result = 0;
        for (int index = 0; index < 5; index++) {
            int current = input.readUnsignedByte();
            result |= (current & 0x7F) << index * 7;
            if ((current & 0x80) == 0) return result;
        }
        throw new ArcticFormatException("VarInt is too long");
    }

    private record DecodedPalette(String[] palette, int[] indices) {
    }
}
