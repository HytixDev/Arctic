package dev.hytixmc.arctic.minestom;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;

final class NbtValidator {
    private static final int MAX_DEPTH = 512;
    private static final long MAX_NODES = 16L * 1024L * 1024L;

    private NbtValidator() {
    }

    static void validate(byte[] bytes) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int rootType = input.readUnsignedByte();
            if (rootType != 10) throw new IOException("NBT root must be a compound");
            input.readUTF();
            Budget budget = new Budget(Math.min(MAX_NODES, Math.max(1024L, bytes.length * 2L)));
            readPayload(input, rootType, 0, budget);
            if (input.available() != 0) throw new IOException("Trailing bytes after NBT root");
        } catch (EOFException exception) {
            throw new IOException("Truncated NBT payload", exception);
        }
    }

    private static void readPayload(DataInputStream input, int type, int depth, Budget budget)
            throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("NBT exceeds maximum depth");
        budget.consume();
        switch (type) {
            case 1 -> skipFully(input, 1);
            case 2 -> skipFully(input, 2);
            case 3, 5 -> skipFully(input, 4);
            case 4, 6 -> skipFully(input, 8);
            case 7 -> skipArray(input, 1);
            case 8 -> input.readUTF();
            case 9 -> {
                int elementType = input.readUnsignedByte();
                int length = readNonNegativeLength(input, "list");
                if (elementType == 0 && length != 0) throw new IOException("NBT end-list must be empty");
                validateType(elementType, length == 0);
                ensureMinimumBytes(input, elementType, length);
                for (int index = 0; index < length; index++) {
                    readPayload(input, elementType, depth + 1, budget);
                }
            }
            case 10 -> {
                while (true) {
                    int childType = input.readUnsignedByte();
                    if (childType == 0) break;
                    validateType(childType, false);
                    input.readUTF();
                    readPayload(input, childType, depth + 1, budget);
                }
            }
            case 11 -> skipArray(input, Integer.BYTES);
            case 12 -> skipArray(input, Long.BYTES);
            default -> throw new IOException("Invalid NBT tag type: " + type);
        }
    }

    private static void skipArray(DataInputStream input, int elementBytes) throws IOException {
        int length = readNonNegativeLength(input, "array");
        final long byteLength;
        try {
            byteLength = Math.multiplyExact((long) length, elementBytes);
        } catch (ArithmeticException exception) {
            throw new IOException("NBT array length overflow", exception);
        }
        if (byteLength > input.available()) throw new EOFException();
        input.skipNBytes(byteLength);
    }

    private static int readNonNegativeLength(DataInputStream input, String kind) throws IOException {
        int length = input.readInt();
        if (length < 0) throw new IOException("Negative NBT " + kind + " length");
        return length;
    }

    private static void ensureMinimumBytes(DataInputStream input, int type, int length)
            throws IOException {
        int minimumPerElement = switch (type) {
            case 0 -> 0;
            case 1 -> 1;
            case 2 -> 2;
            case 3, 5 -> 4;
            case 4, 6 -> 8;
            case 7, 9, 11, 12 -> 4;
            case 8 -> 2;
            case 10 -> 1;
            default -> throw new IOException("Invalid NBT list type: " + type);
        };
        long minimum = Math.multiplyExact((long) minimumPerElement, length);
        if (minimum > input.available()) throw new EOFException();
    }

    private static void validateType(int type, boolean allowEnd) throws IOException {
        if (type == 0 && allowEnd) return;
        if (type < 1 || type > 12) throw new IOException("Invalid NBT tag type: " + type);
    }

    private static void skipFully(DataInputStream input, int bytes) throws IOException {
        if (bytes > input.available()) throw new EOFException();
        input.skipNBytes(bytes);
    }

    private static final class Budget {
        private long remaining;

        private Budget(long remaining) {
            this.remaining = remaining;
        }

        private void consume() throws IOException {
            if (--remaining < 0) throw new IOException("NBT exceeds node budget");
        }
    }
}
