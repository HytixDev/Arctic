package dev.hytixmc.arctic;

/** Compression applied independently to each record. */
public enum ArcticCompression {
    NONE(0),
    ZSTD(1);

    private final int id;

    ArcticCompression(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public static ArcticCompression fromId(int id) {
        return switch (id) {
            case 0 -> NONE;
            case 1 -> ZSTD;
            default -> throw new IllegalArgumentException("Unknown Arctic compression id: " + id);
        };
    }
}
