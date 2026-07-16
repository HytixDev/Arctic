package dev.hytixmc.arctic;

/** The schema used inside an independently compressed chunk record. */
public enum ArcticChunkEncoding {
    BINARY(1),
    NBT(2);

    private final int id;

    ArcticChunkEncoding(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public static ArcticChunkEncoding fromId(int id) {
        return switch (id) {
            case 1 -> BINARY;
            case 2 -> NBT;
            default -> throw new IllegalArgumentException("Unknown Arctic chunk encoding id: " + id);
        };
    }
}
