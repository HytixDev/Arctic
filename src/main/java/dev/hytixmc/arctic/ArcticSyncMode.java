package dev.hytixmc.arctic;

/** Durability policy used when publishing a new committed generation. */
public enum ArcticSyncMode {
    /** Force record data and the selected superblock to stable storage. */
    FULL,
    /** Force record contents but not storage-device metadata. */
    DATA,
    /** Rely on the operating system to flush writes. Fast, but not power-loss safe. */
    NONE
}
