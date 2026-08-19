package com.lifeos.documentvault.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DocumentSearchTokenHasherTest {

    @Test
    void matchesAQueryWithoutPersistingRawContent() {
        String secret = "document-search-test-secret";
        String raw = "Private ledger phrase";

        String encoded = DocumentSearchTokenHasher.encode(secret, raw);

        assertThat(encoded).doesNotContain(raw, "ledger");
        assertThat(DocumentSearchTokenHasher.containsAny(secret, encoded, "ledger"))
                .isTrue();
        assertThat(DocumentSearchTokenHasher.containsAny(secret, encoded, "missing"))
                .isFalse();
        assertThat(DocumentSearchTokenHasher.containsAny("wrong-secret", encoded, "ledger"))
                .isFalse();
    }

    @Test
    void boundsDistinctTokenDigests() {
        String text = java.util.stream.IntStream.range(0, 400)
                .mapToObj(index -> "token" + index)
                .collect(java.util.stream.Collectors.joining(" "));

        String encoded = DocumentSearchTokenHasher.encode("secret", text);

        assertThat(encoded.split(";", -1)).hasSize(258);
    }
}
