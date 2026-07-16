package dev.hytixmc.arctic.minestom;

import dev.hytixmc.arctic.ArcticFormatException;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.ByteArrayBinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class NbtChunkCodec implements ChunkPayloadCodec {
    static final NbtChunkCodec INSTANCE = new NbtChunkCodec();

    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_SECTIONS = 4096;
    private static final int MAX_BLOCK_ENTITIES = 65_536;

    private NbtChunkCodec() {
    }

    @Override
    public byte[] encode(ChunkModel model) throws IOException {
        ListBinaryTag.Builder<CompoundBinaryTag> sections = ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
        for (SectionModel section : model.sections()) {
            CompoundBinaryTag.Builder tag = CompoundBinaryTag.builder()
                    .putInt("Y", section.y())
                    .put("BlockPalette", stringList(section.blockPalette()))
                    .put("BiomePalette", stringList(section.biomePalette()));
            if (section.blockIndices().length != 0) tag.putIntArray("BlockData", section.blockIndices());
            if (section.biomeIndices().length != 0) tag.putIntArray("BiomeData", section.biomeIndices());
            if (section.blockLight().length != 0) tag.putByteArray("BlockLight", section.blockLight());
            if (section.skyLight().length != 0) tag.putByteArray("SkyLight", section.skyLight());
            sections.add(tag.build());
        }

        ListBinaryTag.Builder<CompoundBinaryTag> blockEntities = ListBinaryTag.builder(BinaryTagTypes.COMPOUND);
        for (BlockEntityModel blockEntity : model.blockEntities()) {
            CompoundBinaryTag.Builder tag = CompoundBinaryTag.builder()
                    .putInt("X", blockEntity.x()).putInt("Y", blockEntity.y()).putInt("Z", blockEntity.z());
            if (blockEntity.id() != null) tag.putString("Id", blockEntity.id());
            if (blockEntity.data() != null) tag.put("Data", blockEntity.data());
            blockEntities.add(tag.build());
        }

        CompoundBinaryTag root = CompoundBinaryTag.builder()
                .putInt("ArcticSchema", SCHEMA_VERSION)
                .putInt("X", model.x()).putInt("Z", model.z())
                .putInt("MinSection", model.minSection()).putInt("MaxSection", model.maxSection())
                .put("Sections", sections.build()).put("BlockEntities", blockEntities.build())
                .put("Heightmaps", CompoundBinaryTag.builder()
                        .putLongArray("MOTION_BLOCKING", model.motionBlockingHeightmap())
                        .putLongArray("WORLD_SURFACE", model.worldSurfaceHeightmap()).build())
                .put("UserData", model.userData())
                .build();
        return NbtIo.write(root);
    }

    @Override
    public ChunkModel decode(byte[] payload) throws IOException {
        CompoundBinaryTag root;
        try {
            root = NbtIo.read(payload);
        } catch (IllegalArgumentException exception) {
            throw new ArcticFormatException("Malformed NBT chunk payload", exception);
        }
        if (root.getInt("ArcticSchema") != SCHEMA_VERSION) {
            throw new ArcticFormatException("Unsupported NBT chunk schema: " + root.getInt("ArcticSchema"));
        }
        int minSection = root.getInt("MinSection");
        int maxSection = root.getInt("MaxSection");
        if (maxSection <= minSection || (long) maxSection - minSection > MAX_SECTIONS) {
            throw new ArcticFormatException("Invalid NBT chunk section range");
        }

        ListBinaryTag sectionTags = root.getList("Sections", BinaryTagTypes.COMPOUND);
        if (sectionTags.size() > MAX_SECTIONS) throw new ArcticFormatException("Too many NBT sections");
        List<SectionModel> sections = new ArrayList<>(sectionTags.size());
        for (BinaryTag binaryTag : sectionTags) {
            if (!(binaryTag instanceof CompoundBinaryTag tag)) {
                throw new ArcticFormatException("Non-compound section in NBT chunk");
            }
            String[] blocks = readStringList(tag.getList("BlockPalette", BinaryTagTypes.STRING));
            String[] biomes = readStringList(tag.getList("BiomePalette", BinaryTagTypes.STRING));
            int[] blockData = tag.getIntArray("BlockData");
            int[] biomeData = tag.getIntArray("BiomeData");
            byte[] blockLight = readLight(tag, "BlockLight");
            byte[] skyLight = readLight(tag, "SkyLight");
            sections.add(new SectionModel(tag.getInt("Y"), blocks, blockData, biomes,
                    biomeData, blockLight, skyLight));
        }

        ListBinaryTag blockEntityTags = root.getList("BlockEntities", BinaryTagTypes.COMPOUND);
        if (blockEntityTags.size() > MAX_BLOCK_ENTITIES) {
            throw new ArcticFormatException("Too many NBT block entities");
        }
        List<BlockEntityModel> blockEntities = new ArrayList<>(blockEntityTags.size());
        for (BinaryTag binaryTag : blockEntityTags) {
            if (!(binaryTag instanceof CompoundBinaryTag tag)) {
                throw new ArcticFormatException("Non-compound block entity in NBT chunk");
            }
            String id = tag.get("Id") instanceof StringBinaryTag string ? string.value() : null;
            CompoundBinaryTag data = tag.get("Data") instanceof CompoundBinaryTag compound ? compound : null;
            blockEntities.add(new BlockEntityModel(tag.getInt("X"), tag.getInt("Y"),
                    tag.getInt("Z"), id, data));
        }

        CompoundBinaryTag heightmaps = root.getCompound("Heightmaps");
        CompoundBinaryTag userData = root.get("UserData") instanceof CompoundBinaryTag compound
                ? compound : CompoundBinaryTag.empty();
        return new ChunkModel(root.getInt("X"), root.getInt("Z"), minSection, maxSection,
                List.copyOf(sections), List.copyOf(blockEntities),
                heightmaps.getLongArray("MOTION_BLOCKING"), heightmaps.getLongArray("WORLD_SURFACE"),
                userData);
    }

    private static ListBinaryTag stringList(String[] values) {
        ListBinaryTag.Builder<StringBinaryTag> result = ListBinaryTag.builder(BinaryTagTypes.STRING);
        for (String value : values) result.add(StringBinaryTag.stringBinaryTag(value));
        return result.build();
    }

    private static String[] readStringList(ListBinaryTag list) throws ArcticFormatException {
        if (list.isEmpty()) throw new ArcticFormatException("NBT palette cannot be empty");
        String[] values = new String[list.size()];
        for (int index = 0; index < values.length; index++) values[index] = list.getString(index);
        return values;
    }

    private static byte[] readLight(CompoundBinaryTag tag, String name) throws ArcticFormatException {
        BinaryTag value = tag.get(name);
        if (value == null) return new byte[0];
        if (!(value instanceof ByteArrayBinaryTag bytes) || bytes.size() != 2048) {
            throw new ArcticFormatException("Invalid NBT " + name + " array");
        }
        return bytes.value();
    }
}
