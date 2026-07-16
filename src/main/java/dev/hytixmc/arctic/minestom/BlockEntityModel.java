package dev.hytixmc.arctic.minestom;

import net.kyori.adventure.nbt.CompoundBinaryTag;

record BlockEntityModel(int x, int y, int z, String id, CompoundBinaryTag data) {
}
