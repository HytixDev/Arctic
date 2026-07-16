package dev.hytixmc.arctic.minestom;

record SectionModel(
        int y,
        String[] blockPalette,
        int[] blockIndices,
        String[] biomePalette,
        int[] biomeIndices,
        byte[] blockLight,
        byte[] skyLight) {
}
