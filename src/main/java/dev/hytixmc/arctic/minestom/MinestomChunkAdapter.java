package dev.hytixmc.arctic.minestom;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.Section;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockHandler;
import net.minestom.server.instance.heightmap.Heightmap;
import net.minestom.server.instance.heightmap.MotionBlockingHeightmap;
import net.minestom.server.instance.heightmap.WorldSurfaceHeightmap;
import net.minestom.server.instance.palette.Palette;
import net.minestom.server.registry.RegistryKey;
import net.minestom.server.world.biome.Biome;
import dev.hytixmc.arctic.ArcticFormatException;
import dev.hytixmc.arctic.ArcticWorldAccess;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.function.IntFunction;

final class MinestomChunkAdapter {
    private static final int BLOCK_COUNT = 16 * 16 * 16;
    private static final int BIOME_COUNT = 4 * 4 * 4;
    private static final int LIGHT_BYTES = 2048;

    private MinestomChunkAdapter() {
    }

    static ChunkModel snapshot(Chunk chunk, ArcticWorldAccess worldAccess) throws IOException {
        List<SectionModel> sections = new ArrayList<>(chunk.getSections().size());
        List<BlockEntityModel> blockEntities = new ArrayList<>();
        var biomeRegistry = chunk.getInstance().registries().biome();
        Map<Integer, String> blockNames = new HashMap<>();
        Map<Integer, String> biomeNames = new HashMap<>();
        CompoundBinaryTag userData;
        long[] motionBlocking;
        long[] worldSurface;

        chunk.lockWriteLock();
        try {
            for (int sectionY = chunk.getMinSection(); sectionY < chunk.getMaxSection(); sectionY++) {
                Section section = chunk.getSection(sectionY);
                EncodedPalette blocks = encodePalette(section.blockPalette(), stateId ->
                        blockNames.computeIfAbsent(stateId, id -> blockToString(requireBlock(id))));
                EncodedPalette biomes = encodePalette(section.biomePalette(), biomeId ->
                        biomeNames.computeIfAbsent(biomeId, id -> {
                            RegistryKey<Biome> key = biomeRegistry.getKey(id);
                            if (key == null) throw new IllegalStateException("Unknown biome id: " + id);
                            return key.key().asString();
                        }));
                sections.add(new SectionModel(sectionY, blocks.palette(), blocks.indices(),
                        biomes.palette(), biomes.indices(), copyLight(section.blockLight().array()),
                        copyLight(section.skyLight().array())));

                int baseY = sectionY * Chunk.CHUNK_SECTION_SIZE;
                for (int localY = 0; localY < Chunk.CHUNK_SECTION_SIZE; localY++) {
                    int y = baseY + localY;
                    for (int z = 0; z < Chunk.CHUNK_SIZE_Z; z++) {
                        for (int x = 0; x < Chunk.CHUNK_SIZE_X; x++) {
                            Block block = chunk.getBlock(x, y, z, Block.Getter.Condition.CACHED);
                            if (block == null) continue;
                            BlockHandler handler = block.handler();
                            CompoundBinaryTag nbt = block.nbt();
                            if (handler == null && nbt == null && !block.registry().isBlockEntity()) continue;
                            String handlerId = handler == null ? null : handler.getKey().asString();
                            blockEntities.add(new BlockEntityModel(x, y, z, handlerId, nbt));
                        }
                    }
                }
            }
            int heightmapStart = Heightmap.getHighestBlockSection(chunk);
            Heightmap freshMotionBlocking = new MotionBlockingHeightmap(chunk);
            Heightmap freshWorldSurface = new WorldSurfaceHeightmap(chunk);
            freshMotionBlocking.refresh(heightmapStart);
            freshWorldSurface.refresh(heightmapStart);
            motionBlocking = freshMotionBlocking.getNBT();
            worldSurface = freshWorldSurface.getNBT();
            userData = Objects.requireNonNull(worldAccess.saveChunkData(chunk),
                    "ArcticWorldAccess.saveChunkData returned null");
        } finally {
            chunk.unlockWriteLock();
        }

        return new ChunkModel(chunk.getChunkX(), chunk.getChunkZ(), chunk.getMinSection(),
                chunk.getMaxSection(), List.copyOf(sections), List.copyOf(blockEntities),
                motionBlocking, worldSurface, userData);
    }

    static Chunk createChunk(Instance instance, ChunkModel model, ArcticWorldAccess worldAccess)
            throws IOException {
        validateModel(model);
        Chunk chunk = instance.getChunkSupplier().createChunk(instance, model.x(), model.z());
        if (model.minSection() != chunk.getMinSection() || model.maxSection() != chunk.getMaxSection()) {
            throw new ArcticFormatException("Chunk " + model.x() + "," + model.z()
                    + " has section range [" + model.minSection() + ',' + model.maxSection()
                    + ") but the instance expects [" + chunk.getMinSection() + ','
                    + chunk.getMaxSection() + ")");
        }

        chunk.lockWriteLock();
        try {
            for (SectionModel stored : model.sections()) {
                if (stored.y() < chunk.getMinSection() || stored.y() >= chunk.getMaxSection()) {
                    throw new ArcticFormatException("Section Y is outside chunk bounds: " + stored.y());
                }
                Section section = chunk.getSection(stored.y());
                applyBlockPalette(section.blockPalette(), stored.blockPalette(), stored.blockIndices());
                applyBiomePalette(instance, section.biomePalette(), stored.biomePalette(),
                        stored.biomeIndices());
                applyLight(section, stored.blockLight(), stored.skyLight());
            }

            for (BlockEntityModel blockEntity : model.blockEntities()) {
                if (blockEntity.x() < 0 || blockEntity.x() >= 16 || blockEntity.z() < 0
                        || blockEntity.z() >= 16 || blockEntity.y() < chunk.getMinSection() * 16
                        || blockEntity.y() >= chunk.getMaxSection() * 16) {
                    throw new ArcticFormatException("Block entity is outside chunk bounds");
                }
                Block block = chunk.getBlock(blockEntity.x(), blockEntity.y(), blockEntity.z(),
                        Block.Getter.Condition.TYPE);
                if (block == null) block = Block.AIR;
                if (blockEntity.id() != null) {
                    block = block.withHandler(MinecraftServer.getBlockManager()
                            .getHandlerOrDummy(blockEntity.id()));
                }
                if (blockEntity.data() != null) block = block.withNbt(blockEntity.data());
                chunk.setBlock(blockEntity.x(), blockEntity.y(), blockEntity.z(), block);
            }

            CompoundBinaryTag heightmaps = CompoundBinaryTag.builder()
                    .putLongArray("MOTION_BLOCKING", model.motionBlockingHeightmap())
                    .putLongArray("WORLD_SURFACE", model.worldSurfaceHeightmap())
                    .build();
            chunk.loadHeightmapsFromNBT(heightmaps);
            worldAccess.loadChunkData(chunk, model.userData());
        } finally {
            chunk.unlockWriteLock();
        }
        return chunk;
    }

    private static EncodedPalette encodePalette(Palette source, IntFunction<String> valueToName) {
        int size = source.maxSize();
        int[] sourceValues = new int[size];
        source.getAll((x, y, z, value) -> {
            int dimension = source.dimension();
            sourceValues[x + z * dimension + y * dimension * dimension] = value;
        });

        Map<String, Integer> paletteLookup = new LinkedHashMap<>();
        int[] indices = new int[size];
        for (int index = 0; index < size; index++) {
            String name = valueToName.apply(sourceValues[index]);
            int paletteIndex = paletteLookup.computeIfAbsent(name, ignored -> paletteLookup.size());
            indices[index] = paletteIndex;
        }
        String[] palette = paletteLookup.keySet().toArray(String[]::new);
        return new EncodedPalette(palette, palette.length == 1 ? new int[0] : indices);
    }

    private static void applyBlockPalette(Palette destination, String[] names, int[] indices)
            throws ArcticFormatException {
        validatePalette(names, indices, BLOCK_COUNT, "block");
        int[] values = new int[names.length];
        for (int index = 0; index < names.length; index++) {
            values[index] = parseBlock(names[index]).stateId();
        }
        applyPalette(destination, values, indices);
    }

    private static void applyBiomePalette(Instance instance, Palette destination, String[] names,
                                          int[] indices) throws ArcticFormatException {
        validatePalette(names, indices, BIOME_COUNT, "biome");
        int[] values = new int[names.length];
        for (int index = 0; index < names.length; index++) {
            int id = instance.registries().biome().getId(RegistryKey.unsafeOf(names[index]));
            if (id == -1) throw new ArcticFormatException("Unknown biome: " + names[index]);
            values[index] = id;
        }
        applyPalette(destination, values, indices);
    }

    private static void applyPalette(Palette destination, int[] values, int[] indices)
            throws ArcticFormatException {
        if (values.length == 1) {
            destination.fill(values[0]);
            return;
        }
        int dimension = destination.dimension();
        destination.setAll((x, y, z) -> {
            int dataIndex = x + z * dimension + y * dimension * dimension;
            return values[indices[dataIndex]];
        });
    }

    private static void validatePalette(String[] names, int[] indices, int expectedSize, String type)
            throws ArcticFormatException {
        if (names.length == 0 || names.length > expectedSize) {
            throw new ArcticFormatException("Invalid " + type + " palette size: " + names.length);
        }
        if (names.length == 1) {
            if (indices.length != 0) {
                throw new ArcticFormatException("Single-valued " + type + " palette has index data");
            }
            return;
        }
        if (indices.length != expectedSize) {
            throw new ArcticFormatException("Invalid " + type + " palette index count: " + indices.length);
        }
        for (int index : indices) {
            if (index < 0 || index >= names.length) {
                throw new ArcticFormatException("Invalid " + type + " palette index: " + index);
            }
        }
    }

    private static Block parseBlock(String state) throws ArcticFormatException {
        int propertiesStart = state.indexOf('[');
        String name = propertiesStart == -1 ? state : state.substring(0, propertiesStart);
        Block block = Block.fromKey(name);
        if (block == null) throw new ArcticFormatException("Unknown block state: " + state);
        if (propertiesStart == -1) return block;
        if (!state.endsWith("]")) throw new ArcticFormatException("Malformed block state: " + state);

        String propertiesText = state.substring(propertiesStart + 1, state.length() - 1);
        if (propertiesText.isEmpty()) return block;
        Map<String, String> properties = new HashMap<>();
        for (String property : propertiesText.split(",")) {
            int separator = property.indexOf('=');
            if (separator < 1 || separator == property.length() - 1) {
                throw new ArcticFormatException("Malformed block property in " + state);
            }
            properties.put(property.substring(0, separator), property.substring(separator + 1));
        }
        try {
            return block.withProperties(properties);
        } catch (IllegalArgumentException exception) {
            throw new ArcticFormatException("Invalid block properties in " + state, exception);
        }
    }

    private static String blockToString(Block block) {
        if (block.properties().isEmpty()) return block.name();
        StringBuilder result = new StringBuilder(block.name()).append('[');
        block.properties().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                result.append(entry.getKey()).append('=').append(entry.getValue()).append(','));
        result.setCharAt(result.length() - 1, ']');
        return result.toString();
    }

    private static Block requireBlock(int stateId) {
        Block block = Block.fromStateId(stateId);
        if (block == null) throw new IllegalStateException("Unknown block state id: " + stateId);
        return block;
    }

    private static void validateModel(ChunkModel model) throws ArcticFormatException {
        long sectionCount = (long) model.maxSection() - model.minSection();
        if (sectionCount < 1 || sectionCount > 4096 || model.sections().size() != sectionCount) {
            throw new ArcticFormatException("Chunk does not contain its complete section range");
        }
        Set<Integer> sectionYs = new HashSet<>(model.sections().size());
        for (SectionModel section : model.sections()) {
            if (section.y() < model.minSection() || section.y() >= model.maxSection()
                    || !sectionYs.add(section.y())) {
                throw new ArcticFormatException("Duplicate or out-of-range section Y: " + section.y());
            }
            validatePalette(section.blockPalette(), section.blockIndices(), BLOCK_COUNT, "block");
            validatePalette(section.biomePalette(), section.biomeIndices(), BIOME_COUNT, "biome");
            validateLight(section.blockLight());
            validateLight(section.skyLight());
        }

        Set<BlockPosition> blockPositions = new HashSet<>(model.blockEntities().size());
        for (BlockEntityModel blockEntity : model.blockEntities()) {
            if (!blockPositions.add(new BlockPosition(blockEntity.x(), blockEntity.y(), blockEntity.z()))) {
                throw new ArcticFormatException("Duplicate block entity position");
            }
        }

        int height = Math.multiplyExact((int) sectionCount, Chunk.CHUNK_SECTION_SIZE);
        int bits = Math.max(1, Integer.SIZE - Integer.numberOfLeadingZeros(height));
        int entriesPerLong = Long.SIZE / bits;
        int expectedHeightmapLongs = (256 + entriesPerLong - 1) / entriesPerLong;
        if (model.motionBlockingHeightmap().length != expectedHeightmapLongs
                || model.worldSurfaceHeightmap().length != expectedHeightmapLongs) {
            throw new ArcticFormatException("Invalid heightmap array length");
        }
    }

    private static byte[] copyLight(byte[] light) throws ArcticFormatException {
        if (light == null || light.length == 0) return new byte[0];
        if (light.length != LIGHT_BYTES) {
            throw new ArcticFormatException("Invalid Minestom light array length: " + light.length);
        }
        return Arrays.copyOf(light, light.length);
    }

    private static void applyLight(Section section, byte[] blockLight, byte[] skyLight)
            throws ArcticFormatException {
        validateLight(blockLight);
        validateLight(skyLight);
        section.setBlockLight(Arrays.copyOf(blockLight, blockLight.length));
        section.setSkyLight(Arrays.copyOf(skyLight, skyLight.length));
    }

    private static void validateLight(byte[] light) throws ArcticFormatException {
        if (light.length != 0 && light.length != LIGHT_BYTES) {
            throw new ArcticFormatException("Invalid stored light array length: " + light.length);
        }
    }

    private record EncodedPalette(String[] palette, int[] indices) {
    }

    private record BlockPosition(int x, int y, int z) {
    }
}
