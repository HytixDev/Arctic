package dev.hytixmc.arctic.minestom;

import net.kyori.adventure.nbt.CompoundBinaryTag;

import java.util.List;

record ChunkModel(
        int x,
        int z,
        int minSection,
        int maxSection,
        List<SectionModel> sections,
        List<BlockEntityModel> blockEntities,
        long[] motionBlockingHeightmap,
        long[] worldSurfaceHeightmap,
        CompoundBinaryTag userData) {
}
