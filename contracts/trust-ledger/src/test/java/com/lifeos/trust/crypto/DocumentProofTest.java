package com.lifeos.trust.crypto;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DocumentProofTest {

    private static final Hash32 DIGEST = new Hash32(new byte[Hash32.BYTE_LENGTH]);

    @Test
    void rejectsUnsupportedProofMetadata() {
        assertThrows(IllegalArgumentException.class, () -> new DocumentProof("MD5", DIGEST, 1));
        assertThrows(NullPointerException.class, () -> new DocumentProof(DocumentHasher.ALGORITHM, null, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DocumentProof(DocumentHasher.ALGORITHM, DIGEST, 0));
    }
}
