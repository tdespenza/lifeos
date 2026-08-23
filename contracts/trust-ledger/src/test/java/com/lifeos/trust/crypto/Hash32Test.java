package com.lifeos.trust.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class Hash32Test {

    @Test
    void roundTripsCanonicalHexAndDefensivelyCopiesInput() {
        byte[] bytes = new byte[Hash32.BYTE_LENGTH];
        bytes[0] = (byte) 0xff;
        Hash32 hash = new Hash32(bytes);
        bytes[0] = 0;
        byte[] exposedBytes = hash.bytes();
        exposedBytes[0] = 0;

        assertEquals('f', hash.toHex().charAt(0));
        assertEquals((byte) 0xff, hash.bytes()[0]);
        assertEquals(hash, Hash32.fromHex(hash.toHex().toUpperCase(java.util.Locale.ROOT)));
        assertThrows(IllegalArgumentException.class, () -> Hash32.fromHex("not-a-hash"));
    }

    @Test
    void rejectsInvalidDigestByteLengths() {
        assertThrows(IllegalArgumentException.class, () -> new Hash32(null));
        assertThrows(IllegalArgumentException.class, () -> new Hash32(new byte[Hash32.BYTE_LENGTH - 1]));
        assertThrows(IllegalArgumentException.class, () -> new Hash32(new byte[Hash32.BYTE_LENGTH + 1]));
    }
}
