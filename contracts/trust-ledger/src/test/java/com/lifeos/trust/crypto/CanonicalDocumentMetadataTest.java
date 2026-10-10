package com.lifeos.trust.crypto;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CanonicalDocumentMetadataTest {

    @Test
    void rejectsNullMetadataTokens() {
        assertThrows(
                NullPointerException.class,
                () -> new CanonicalDocumentMetadata(null, "document-proof"));
        assertThrows(
                NullPointerException.class,
                () -> new CanonicalDocumentMetadata("application/pdf", null));
    }

    @Test
    void rejectsBlankUnsafeAndOverLengthMetadataTokens() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CanonicalDocumentMetadata("", "document-proof"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CanonicalDocumentMetadata("application pdf", "document-proof"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CanonicalDocumentMetadata("a".repeat(128), "document-proof"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CanonicalDocumentMetadata("application/pdf", "a".repeat(65)));
    }
}
