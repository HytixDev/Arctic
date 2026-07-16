# Arctic

> [!WARNING]
> This project was entirely built with AI assistance and is provided as-is. It may contain bugs or unexpected behavior and is not guaranteed to work in all environments. Use at your own risk. 

Arctic is a seekable, streamed world format and `ChunkLoader` for Minestom. It keeps one world in one `.arctic` file without requiring the complete world to be loaded or rewritten.

Unlike Polar, every chunk is an independent record. Loading one chunk performs one index lookup, one positional file read, one checksum verification, and at most one bounded Zstd decompression. Saving appends only changed chunks and atomically publishes a new generation.

## Features

- Java 25 and Minestom `2026.07.12-26.2`
- Lazy chunk reads; chunk bodies never load during file open
- 64-bit file offsets and lengths
- Independent `NONE` or Zstd compression per chunk
- Compact palette-based binary chunks
- Fully NBT-encoded chunks as an alternative
- Binary and NBT chunk records may coexist in one file
- Blocks, block states, biomes, lights, block entities, heightmaps, and chunk/world extension data
- Append-only updates and tombstone deletion
- Atomic batch publication through alternating checksummed superblocks
- Automatic index checkpoints with bounded delta replay on open
- Per-record CRC32C corruption detection and chunk-level failure isolation
- Exclusive process lock, size limits, NBT allocation limits, and compaction

The coordinate index remains resident in memory; chunk payloads do not. A checkpoint entry is 32 bytes on disk, while the Java concurrent-map representation has additional object overhead. This makes Arctic suitable for worlds far larger than whole-world formats, but deployments with tens of millions of chunks should budget index memory or introduce a paged-index container version.

## Setup

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("dev.hytixmc:arctic:1.0.0")
}
```

Until the artifact is published, include this project as a Gradle composite/subproject.

## Minestom usage

Pass the loader when creating the instance so Minestom invokes `loadInstance` during construction:

```java
Path path = Path.of("worlds", "survival.arctic");
ArcticLoader loader = new ArcticLoader(path);

InstanceContainer instance = MinecraftServer.getInstanceManager()
        .createInstanceContainer(loader);

// Normal Minestom APIs load chunks lazily through ArcticLoader.
instance.loadChunk(0, 0).join();
```

Save and close during shutdown:

```java
instance.saveInstance();
instance.saveChunksToStorage();
loader.close();
```

If the loader is attached later with `instance.setChunkLoader(loader)`, call `loader.loadInstance(instance)` explicitly; current Minestom does not invoke it from the setter.

## NBT chunk mode

Binary chunks are the default. To store each chunk payload as standard uncompressed big-endian NBT inside its independently compressed Arctic record:

```java
ArcticOptions options = ArcticOptions.builder()
        .chunkEncoding(ArcticChunkEncoding.NBT)
        .compression(ArcticCompression.ZSTD)
        .compressionLevel(3)
        .build();

ArcticLoader loader = new ArcticLoader(path, options);
```

The record identifies its encoding, so changing the option affects future saves only. Previously saved binary and NBT chunks remain readable.

## Application data and entities

`ArcticWorldAccess` persists Minestom tag handlers by default. Supply an implementation to store namespaced application data, entity snapshots, structures, scheduled tasks, or another schema in the world/chunk `UserData` NBT compounds:

```java
ArcticWorldAccess access = new ArcticWorldAccess() {
    @Override
    public CompoundBinaryTag saveChunkData(Chunk chunk) {
        return CompoundBinaryTag.builder()
                .put(chunk.tagHandler().asCompound())
                .put("myserver:entities", saveEntities(chunk))
                .build();
    }

    @Override
    public void loadChunkData(Chunk chunk, CompoundBinaryTag data) {
        chunk.tagHandler().updateContent(data);
        loadEntities(chunk, data.getList("myserver:entities"));
    }
};

ArcticLoader loader = ArcticLoader.builder(path)
        .worldAccess(access)
        .open();
```

Callbacks may run concurrently and chunk callbacks execute while the chunk lock is held. Implementations must be thread-safe and must not recursively lock the same chunk through an instance operation.

Arctic does not instantiate arbitrary Minestom entities by default because entity subclasses require application-specific constructors and metadata. The format provides the NBT extension field needed to preserve them without changing the container schema.

## Tuning

```java
ArcticOptions options = ArcticOptions.builder()
        .syncMode(ArcticSyncMode.FULL)
        .checkpointInterval(4096)
        .saveBatchChunkLimit(64)
        .saveBatchByteLimit(64 * 1024 * 1024)
        .maxCompressedChunkBytes(32 * 1024 * 1024)
        .maxUncompressedChunkBytes(128 * 1024 * 1024)
        .maxWorldDataBytes(16 * 1024 * 1024)
        .build();
```

- `FULL` is the default and is required for the strongest power-loss guarantee.
- `DATA` forces file contents but may not durably persist file-size metadata on every platform.
- `NONE` relies on operating-system flushing and is not power-loss safe.
- Minestom bulk saves are split into bounded transactions. Each batch is atomic; an entire multi-batch save is not one transaction.
- Lower checkpoint intervals reduce reopen replay work but write the full coordinate index more often.

## Maintenance

```java
loader.deleteChunk(chunkX, chunkZ);       // durable tombstone
loader.checkpoint();                      // force an index checkpoint
loader.compactTo(Path.of("world-new.arctic"));
```

`compactTo` creates a new file containing only live records. Verify and atomically replace the original after closing both files according to your deployment policy.

## Compatibility and trust

- Container, chunk-schema, and Minecraft `DataVersion` are independent.
- Arctic rejects future container/chunk schemas and Minecraft data versions.
- Older `DataVersion` values are read using namespaced block states and biome keys; no Mojang data fixer is bundled.
- Unknown blocks and biomes fail loudly instead of silently replacing persistent data.
- CRC32C detects accidental corruption; it is not cryptographic authentication. Treat uploaded/untrusted files as untrusted input and apply deployment-level authorization or signatures.
- Durable creation still depends on filesystem/platform guarantees for the parent directory entry.

See [`FORMAT.md`](FORMAT.md) for the normative v1 byte layout.


## Acknowledgements

[Polar](https://github.com/hollow-cube/polar) for the original Polar file format.
[Minestom](https://github.com/Minestom/Minestom) for the Minecraft server implementation.
