package com.lifeos.assistant.tool;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.beans.factory.annotation.Autowired;

/** Records explicit tool confirmation without retaining title, dates, or other raw request data. */
@Service
public class AssistantToolConfirmationService {

    private final AssistantToolConfirmationTransactions transactions;

    @Autowired
    public AssistantToolConfirmationService(AssistantToolConfirmationTransactions transactions) {
        this.transactions = transactions;
    }

    AssistantToolConfirmationService(AssistantToolConfirmationRepository repository) {
        this.transactions = new AssistantToolConfirmationTransactions(repository);
    }

    public void confirm(
            UUID conversationId,
            UUID ownerAccountId,
            AssistantToolOperation operation,
            String title,
            Integer priority,
            Instant dueAt,
            String idempotencyKey) {
        String keyHash = sha256(idempotencyKey);
        String requestFingerprint = sha256(canonical(operation, title, priority, dueAt));
        var existing = findExisting(conversationId, ownerAccountId, keyHash);
        if (existing.isPresent()) {
            requireMatching(existing.get().getRequestFingerprint(), requestFingerprint);
            return;
        }
        try {
            transactions.reserve(conversationId, ownerAccountId, operation, keyHash, requestFingerprint);
        } catch (org.springframework.dao.DataIntegrityViolationException exception) {
            var winner = findExisting(conversationId, ownerAccountId, keyHash);
            if (winner.isEmpty()) {
                throw exception;
            }
            requireMatching(winner.get().getRequestFingerprint(), requestFingerprint);
        }
    }

    private java.util.Optional<AssistantToolConfirmation> findExisting(
            UUID conversationId, UUID ownerAccountId, String keyHash) {
        try {
            return transactions.findExisting(conversationId, ownerAccountId, keyHash);
        } catch (DataAccessException | TransactionTimedOutException exception) {
            throw new AssistantToolConfirmationUnavailableException(exception);
        }
    }

    private static void requireMatching(String expected, String actual) {
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII))) {
            throw new AssistantToolConfirmationConflictException();
        }
    }

    private static String canonical(
            AssistantToolOperation operation, String title, Integer priority, Instant dueAt) {
        return lengthPrefix(operation.name())
                + lengthPrefix(title)
                + lengthPrefix(priority == null ? "" : priority.toString())
                + lengthPrefix(dueAt == null ? "" : dueAt.toString());
    }

    private static String lengthPrefix(String value) {
        return value.length() + ":" + value;
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
