package dev.hytixmc.arctic;

import java.io.IOException;

/** Indicates malformed, unsupported, or corrupted Arctic data. */
public final class ArcticFormatException extends IOException {
    private static final long serialVersionUID = 1L;

    public ArcticFormatException(String message) {
        super(message);
    }

    public ArcticFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
