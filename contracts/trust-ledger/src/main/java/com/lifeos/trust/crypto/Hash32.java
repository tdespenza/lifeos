package com.lifeos.trust.crypto;

import java.security.MessageDigest;
import java.util.Arrays;

/** Immutable 32-byte SHA-256-compatible digest value with strict lower-case hexadecimal encoding. */
public final class Hash32 {

    public static final int BYTE_LENGTH = 32;
    private final byte[] bytes;

    /**
     * Creates a defensive immutable digest value.
     *
     * @param bytes exactly 32 digest bytes
     */
    public Hash32(byte[] bytes) {
        if (bytes == null || bytes.length != BYTE_LENGTH) {
            throw new IllegalArgumentException("digest must contain exactly 32 bytes");
        }
        this.bytes = bytes.clone();
    }

    /** Parses exactly 64 lower- or upper-case hexadecimal characters. */
    public static Hash32 fromHex(String value) {
        if (value == null || !value.matches("[0-9A-Fa-f]{64}")) {
            throw new IllegalArgumentException("digest must be 64 hexadecimal characters");
        }
        byte[] decoded = new byte[BYTE_LENGTH];
        for (int index = 0; index < decoded.length; index++) {
            decoded[index] = (byte) Integer.parseInt(value.substring(index * 2, index * 2 + 2), 16);
        }
        return new Hash32(decoded);
    }

    /** Returns a defensive digest-byte copy for standard JCA verification APIs. */
    public byte[] bytes() {
        return bytes.clone();
    }

    /** Returns the canonical lower-case hexadecimal representation. */
    public String toHex() {
        StringBuilder encoded = new StringBuilder(BYTE_LENGTH * 2);
        for (byte value : bytes) {
            encoded.append(Character.forDigit((value >>> 4) & 0x0f, 16));
            encoded.append(Character.forDigit(value & 0x0f, 16));
        }
        return encoded.toString();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Hash32 hash && MessageDigest.isEqual(bytes, hash.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return toHex();
    }
}
