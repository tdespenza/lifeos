package com.lifeos.assistant.tool;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AssistantToolConfirmationServiceTest {

    @Mock
    private AssistantToolConfirmationRepository repository;

    @Test
    void storesOnlyKeyedConfirmationMetadata() {
        var service = new AssistantToolConfirmationService(repository);
        UUID conversationId = UUID.randomUUID();
        UUID ownerAccountId = UUID.randomUUID();

        service.confirm(
                conversationId,
                ownerAccountId,
                AssistantToolOperation.DRAFT_GOAL,
                "Private title",
                2,
                Instant.parse("2026-08-19T15:00:00Z"),
                "confirmation-key");

        ArgumentCaptor<AssistantToolConfirmation> captor = ArgumentCaptor.forClass(AssistantToolConfirmation.class);
        verify(repository).saveAndFlush(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getRequestFingerprint()).hasSize(64);
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getRequestFingerprint()).doesNotContain("Private title");
    }

    @Test
    void matchingRetryDoesNotInsertAnotherLedgerRow() {
        var service = new AssistantToolConfirmationService(repository);
        UUID conversationId = UUID.randomUUID();
        UUID ownerAccountId = UUID.randomUUID();
        String key = "confirmation-key";
        service.confirm(conversationId, ownerAccountId, AssistantToolOperation.DRAFT_TASK, "Call dentist", 3, null, key);
        ArgumentCaptor<AssistantToolConfirmation> captor = ArgumentCaptor.forClass(AssistantToolConfirmation.class);
        verify(repository).saveAndFlush(captor.capture());
        when(repository.findByConversationIdAndOwnerAccountIdAndIdempotencyKeyHash(
                        conversationId, ownerAccountId, hash(key)))
                .thenReturn(Optional.of(captor.getValue()));

        service.confirm(conversationId, ownerAccountId, AssistantToolOperation.DRAFT_TASK, "Call dentist", 3, null, key);

        verify(repository, times(1)).saveAndFlush(any(AssistantToolConfirmation.class));
    }

    @Test
    void rejectsSameKeyWithDifferentPayload() {
        var service = new AssistantToolConfirmationService(repository);
        UUID conversationId = UUID.randomUUID();
        UUID ownerAccountId = UUID.randomUUID();
        String key = "confirmation-key";
        service.confirm(conversationId, ownerAccountId, AssistantToolOperation.DRAFT_TASK, "Call dentist", 3, null, key);
        ArgumentCaptor<AssistantToolConfirmation> captor = ArgumentCaptor.forClass(AssistantToolConfirmation.class);
        verify(repository).saveAndFlush(captor.capture());
        when(repository.findByConversationIdAndOwnerAccountIdAndIdempotencyKeyHash(
                        conversationId, ownerAccountId, hash(key)))
                .thenReturn(Optional.of(captor.getValue()));

        assertThatThrownBy(() -> service.confirm(
                        conversationId, ownerAccountId, AssistantToolOperation.DRAFT_GOAL, "Book vacation", 3, null, key))
                .isInstanceOf(AssistantToolConfirmationConflictException.class);
    }

    private static String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
