package dev.hytixmc.arctic;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;
import net.minestom.server.tag.Tag;
import net.minestom.server.world.biome.Biome;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ArcticLoaderTest {
    private static final Tag<String> TEST_TAG = Tag.String("arctic-test");

    @TempDir
    Path temporaryDirectory;

    @BeforeAll
    static void initializeMinestom() {
        MinecraftServer.init();
    }

    @AfterAll
    static void stopMinestom() {
        MinecraftServer.stopCleanly();
    }

    @Test
    void roundTripsMinestomChunksWithBothEncodings() throws IOException {
        roundTrip(ArcticChunkEncoding.BINARY);
        roundTrip(ArcticChunkEncoding.NBT);
    }

    private void roundTrip(ArcticChunkEncoding encoding) throws IOException {
        Path path = temporaryDirectory.resolve(encoding.name().toLowerCase() + ".arctic");
        ArcticOptions options = ArcticOptions.builder().chunkEncoding(encoding).build();
        InstanceContainer source = MinecraftServer.getInstanceManager().createInstanceContainer();
        Chunk chunk = source.getChunkSupplier().createChunk(source, 7, -4);
        byte[] blockLight = new byte[2048];
        blockLight[17] = 42;

        chunk.lockWriteLock();
        try {
            chunk.setBlock(1, 10, 2, Block.OAK_STAIRS.withProperty("facing", "west"));
            chunk.setBlock(3, 11, 4, Block.CHEST.withNbt(CompoundBinaryTag.builder()
                    .putString("CustomName", "Arctic chest").build()));
            chunk.setBlock(5, 12, 6, Block.CHEST);
            chunk.setBiome(1, 10, 2, Biome.DESERT);
            chunk.getSectionAt(10).setBlockLight(blockLight);
            chunk.setTag(TEST_TAG, "chunk-data");
            chunk.getSection(1).blockPalette().fill(Block.STONE.stateId());
        } finally {
            chunk.unlockWriteLock();
        }

        try (ArcticLoader loader = new ArcticLoader(path, options)) {
            loader.saveChunk(chunk);
            source.setTag(TEST_TAG, "world-data");
            loader.saveInstance(source);
        }

        InstanceContainer target = MinecraftServer.getInstanceManager().createInstanceContainer();
        Chunk loaded;
        try (ArcticLoader loader = new ArcticLoader(path, options)) {
            loader.loadInstance(target);
            loaded = loader.loadChunk(target, 7, -4);
        }

        assertNotNull(loaded);
        loaded.lockReadLock();
        try {
            assertEquals("west", loaded.getBlock(1, 10, 2).getProperty("facing"));
            Block chest = loaded.getBlock(3, 11, 4);
            assertNotNull(chest.nbt());
            assertEquals("Arctic chest", chest.nbt().getString("CustomName"));
            assertEquals(Biome.DESERT, loaded.getBiome(1, 10, 2));
            assertNotNull(loaded.getBlock(5, 12, 6, Block.Getter.Condition.CACHED));
            assertEquals(31, loaded.motionBlockingHeightmap().getHeight(0, 0));
            assertEquals(31, loaded.worldSurfaceHeightmap().getHeight(0, 0));
            assertArrayEquals(blockLight, loaded.getSectionAt(10).blockLight().array());
            assertEquals("chunk-data", loaded.getTag(TEST_TAG));
        } finally {
            loaded.unlockReadLock();
        }
        assertEquals("world-data", target.getTag(TEST_TAG));
    }
}
