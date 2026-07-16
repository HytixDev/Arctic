package dev.hytixmc.arctic;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;

/**
 * Extension hooks for application-owned world and chunk data.
 *
 * <p>The default implementation persists Minestom tag handlers. Applications may return a
 * namespaced compound containing entity, quest, structure, or other server-specific state.</p>
 */
public interface ArcticWorldAccess {
    ArcticWorldAccess DEFAULT = new ArcticWorldAccess() {
    };

    default CompoundBinaryTag saveWorldData(Instance instance) {
        return instance.tagHandler().asCompound();
    }

    default void loadWorldData(Instance instance, CompoundBinaryTag data) {
        instance.tagHandler().updateContent(data);
    }

    default CompoundBinaryTag saveChunkData(Chunk chunk) {
        return chunk.tagHandler().asCompound();
    }

    default void loadChunkData(Chunk chunk, CompoundBinaryTag data) {
        chunk.tagHandler().updateContent(data);
    }
}
