package com.lifeos.documentvault.proof;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentProofRequestTest {

    @Test
    void exhaustedPublicationCompensatesToAStableTerminalFailure() {
        DocumentProofRequest request = new DocumentProofRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "personal-tenant",
                3,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                Instant.parse("2026-08-19T12:00:00Z"));

        assertThat(request.getState()).isEqualTo(DocumentProofRequestState.REQUESTED);

        request.markFailed();
        request.markFailed();

        assertThat(request.getState()).isEqualTo(DocumentProofRequestState.FAILED);
    }
}
