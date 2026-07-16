# Arctic container format v1

This document is normative for container version 1 and chunk schema version 1.

## Conventions

- All fixed-width integers are signed, two's-complement, big-endian values unless stated otherwise.
- Offsets and lengths are 64-bit and are measured in bytes from the start of the file.
- Records begin on an 8-byte boundary and are padded with zeroes to the next boundary.
- `CRC32C` uses the Castagnoli polynomial and is stored as a 32-bit value.
- `VarInt` is an unsigned LEB128 integer with at most five bytes.
- `string` is a VarInt byte length followed by UTF-8 bytes.
- NBT means uncompressed, big-endian, named-root Java Edition NBT. Container compression is applied outside NBT.

## File layout

```text
0x0000  Superblock A (4096 bytes)
0x1000  Superblock B (4096 bytes)
0x2000  Append-only records
...     Committed end from the active superblock
...     Optional uncommitted tail, ignored on recovery
```

The active superblock is the fully usable superblock with the highest generation. Readers validate candidates newest-first and may fall back to the older generation if the newer one is physically impossible, has invalid record structure, or references a corrupt index checkpoint.

## Superblock

Each superblock is exactly 4096 bytes.

| Offset | Size | Field | Description |
|---:|---:|---|---|
| 0 | 8 | Magic | bytes `41 52 43 54 49 43 0D 0A` (`ARCTIC` + CRLF) |
| 8 | 4 | Container version | `1` |
| 12 | 4 | Superblock size | `4096` |
| 16 | 8 | Generation | Monotonically increasing commit generation |
| 24 | 8 | Committed length | Exclusive logical end of the committed file |
| 32 | 8 | Checkpoint offset | `0`, or index record offset |
| 40 | 8 | Checkpoint length | `0`, or complete aligned index record length |
| 48 | 8 | World-data offset | `0`, or latest world-data record offset |
| 56 | 8 | World-data length | `0`, or complete aligned world-data record length |
| 64 | 8 | File UUID MSB | Stable file identity |
| 72 | 8 | File UUID LSB | Stable file identity |
| 80 | 8 | Feature flags | `0` in v1 |
| 88 | 4004 | Reserved | Zero when written; ignored by v1 readers |
| 4092 | 4 | Superblock CRC32C | CRC over all 4096 bytes with this field zeroed |

An offset/length pointer is either `(0, 0)` or both fields are nonzero and the complete record lies inside `committed length`.

## Record header

Every record starts with the following 80-byte header.

| Offset | Size | Field | Description |
|---:|---:|---|---|
| 0 | 4 | Record magic | ASCII `ARCR` (`0x41524352`) |
| 4 | 2 | Header size | `80` |
| 6 | 1 | Record type | See below |
| 7 | 1 | Payload encoding | Chunk encoding, otherwise `0` |
| 8 | 1 | Compression | `0=None`, `1=Zstd` |
| 9 | 1 | Flags | `0` in v1 |
| 10 | 2 | Reserved | `0` |
| 12 | 4 | Schema version | Payload schema version |
| 16 | 4 | Minecraft DataVersion | Chunk records; otherwise `0` |
| 20 | 4 | Chunk X | Chunk/tombstone records; otherwise `0` |
| 24 | 4 | Chunk Z | Chunk/tombstone records; otherwise `0` |
| 28 | 8 | Generation | Commit generation that wrote the record |
| 36 | 8 | Uncompressed length | Decoded payload byte length |
| 44 | 8 | Stored length | Bytes physically following the header, excluding padding |
| 52 | 4 | Payload CRC32C | CRC over stored bytes (compressed bytes when compressed) |
| 56 | 4 | Header CRC32C | CRC over all 80 header bytes with this field zeroed |
| 60 | 20 | Reserved | Zero when written; ignored by v1 readers |

The complete record length is:

```text
align8(80 + storedLength)
```

Padding is not checksummed and has no semantic value.

### Record types

| ID | Name | Meaning |
|---:|---|---|
| 1 | Chunk | Latest chunk revision when selected by the index |
| 2 | Tombstone | Deletes the coordinate from the logical index; payload is empty |
| 3 | World data | Latest world metadata selected by the superblock |
| 4 | Index checkpoint | Snapshot of all live chunk pointers |

### Chunk payload encodings

| ID | Name |
|---:|---|
| 1 | Arctic binary chunk v1 |
| 2 | Arctic NBT chunk v1 |

A file may contain both encodings. The writer option selects only the encoding of new chunk revisions.

## Commit protocol

For generation `N + 1`, a writer:

1. Appends all new records without modifying generation `N` records.
2. Optionally appends a full index checkpoint.
3. Forces record bytes according to `ArcticSyncMode`.
4. Writes the inactive superblock with generation `N + 1` and the new committed boundary.
5. Forces the new superblock.
6. Makes the new generation visible in memory.

Before step 4, failure safely leaves generation `N` authoritative and the tail may be truncated. Once step 4 starts, success is ambiguous until reopen if an I/O operation fails. Implementations must not truncate in that state; this implementation poisons/closes the handle and resolves the newest fully usable generation on reopen.

`FULL` requests metadata and content persistence for both force operations. `DATA` and `NONE` provide weaker guarantees as described in `ArcticSyncMode`.

## Index checkpoint payload

Index checkpoints are uncompressed in v1 so they can be streamed without a Java array-size limit.

| Size | Field |
|---:|---|
| 4 | Index magic `AIDX` (`0x41494458`) |
| 4 | Index version `1` |
| 8 | Entry count |
| `count * 32` | Entries |

Each entry is:

| Size | Field |
|---:|---|
| 4 | Chunk X |
| 4 | Chunk Z |
| 8 | Chunk-record offset |
| 8 | Complete aligned chunk-record length |
| 8 | Chunk-record generation |

On open, the reader loads the selected checkpoint and scans records between the end of that checkpoint and the committed boundary. Chunk records replace coordinates and tombstones remove them. Automatic checkpoints bound this replay to approximately `checkpointInterval` changed records.

The checkpoint is an accelerator, not the source of chunk content. Chunk headers and payload checksums are revalidated when read.

## World-data payload v1

World data is an NBT compound:

```text
{
  ArcticWorldSchema: 1,
  DataVersion: int,
  MinSection: int,       // inclusive
  MaxSection: int,       // exclusive
  UserData: compound
}
```

The default Minestom access stores `Instance.tagHandler()` in `UserData`. Custom `ArcticWorldAccess` implementations own that compound's application schema.

## Arctic binary chunk v1

### Chunk prelude

| Type | Field |
|---|---|
| int | Magic `ACB1` (`0x41434231`) |
| int | Chunk X |
| int | Chunk Z |
| int | Minimum section, inclusive |
| int | Maximum section, exclusive |
| VarInt | Section count |
| section[] | Exactly one section for every Y in the range |
| VarInt | Block-entity count |
| block-entity[] | Block entities/cache markers |
| long-array | `MOTION_BLOCKING` heightmap |
| long-array | `WORLD_SURFACE` heightmap |
| byte-array | NBT `UserData` compound |

`byte-array` and `long-array` start with a VarInt element count. Heightmaps use Minestom/Mojang padded packing: `floor(64 / bitsPerEntry)` entries per long, where `bitsPerEntry = bitsToRepresent(worldHeight)`.

### Section

| Type | Field |
|---|---|
| int | Explicit section Y |
| palette | Block-state palette and 4096 indices |
| palette | Biome-key palette and 64 indices |
| light | Block light |
| light | Sky light |

Block palette strings use:

```text
namespace:block[property1=value1,property2=value2]
```

Properties are emitted in lexical key order. Biomes use namespaced registry keys such as `minecraft:plains`.

### Palette

| Type | Field |
|---|---|
| VarInt | Palette size, at least 1 |
| string[] | Palette values |
| byte | Bits per entry; omitted for a singleton palette |
| VarInt | Packed long count; omitted for a singleton palette |
| long[] | Packed indices; omitted for a singleton palette |

`bitsPerEntry = max(1, bitsToRepresent(paletteSize - 1))`. Packing is padded: `floor(64 / bitsPerEntry)` values per long and no value crosses a long boundary. Singleton palettes imply that every value uses palette entry zero.

### Light

| Marker | Meaning | Following bytes |
|---:|---|---:|
| 0 | Missing/invalidated | 0 |
| 1 | All zero | 0 |
| 2 | All maximum (`0xFF`) | 0 |
| 3 | Present | 2048 |

### Block entity

| Type | Field |
|---|---|
| unsigned byte | Local X (`0..15`) |
| int | Absolute block Y |
| unsigned byte | Local Z (`0..15`) |
| boolean | Handler ID present |
| string? | Namespaced handler ID |
| boolean | NBT present |
| byte-array? | NBT compound |

An entry with no handler and no NBT is a cache marker for registry-defined block entities such as a plain chest. This is required because Minestom's client chunk packet derives block entities from its cached block map.

## Arctic NBT chunk v1

The complete payload is a named-root NBT compound:

```text
{
  ArcticSchema: 1,
  X: int,
  Z: int,
  MinSection: int,
  MaxSection: int,
  Sections: [
    {
      Y: int,
      BlockPalette: [string],
      BlockData: [I; ...],       // omitted for singleton palette
      BiomePalette: [string],
      BiomeData: [I; ...],       // omitted for singleton palette
      BlockLight: [B; 2048],     // optional
      SkyLight: [B; 2048]        // optional
    }
  ],
  BlockEntities: [
    {
      X: int,
      Y: int,
      Z: int,
      Id: string,                // optional
      Data: compound             // optional
    }
  ],
  Heightmaps: {
    MOTION_BLOCKING: [L; ...],
    WORLD_SURFACE: [L; ...]
  },
  UserData: compound
}
```

NBT mode favors interoperability and inspectability. Its integer palette-index arrays are intentionally straightforward; record-level Zstd recovers most repeated-data space.

## Validation requirements

A conforming v1 reader must reject:

- invalid superblock/header/payload checksums;
- records outside the selected committed boundary;
- negative, overflowing, or configured-over-limit lengths;
- unsupported compression, encoding, or schema IDs;
- future unsupported Minecraft `DataVersion` values;
- incomplete, duplicate, or out-of-range section sets;
- empty/oversized palettes or out-of-range palette indices;
- duplicate/out-of-range block entities;
- light arrays other than 0 or 2048 bytes;
- heightmap arrays with a length inconsistent with world height;
- NBT that exceeds allocation/depth limits or contains trailing bytes.

Readers should fail one corrupt chunk without making unrelated chunks unreadable. A corrupt active checkpoint may cause fallback to the previous committed generation.

## Deletion and compaction

Deletion appends a tombstone; old chunk revisions remain physically present but unreachable. World-data replacements and old checkpoints are likewise retained. Compaction writes a new file containing current world data and live chunks, then writes a fresh checkpoint. Replacement of the source file is intentionally left to deployment code so it can use platform-appropriate verification, atomic move, and directory synchronization.

## Forward compatibility

- Container version changes file-level structure.
- Record schema version changes one payload type.
- Minecraft `DataVersion` identifies game registry/data semantics.
- Reserved fields must be written as zero and ignored by v1 readers.
- Unknown record types are not safely skippable in v1's committed log and must be rejected.
- New optional application data belongs in namespaced `UserData` NBT compounds.
