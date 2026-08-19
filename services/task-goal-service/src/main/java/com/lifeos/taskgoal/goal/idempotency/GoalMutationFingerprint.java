package com.lifeos.taskgoal.goal.idempotency;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Produces unambiguous, domain-separated digests for retryable goal lifecycle commands. */
final class GoalMutationFingerprint {

    private static final byte[] KEY_DOMAIN = "lifeos:goal-mutation:idempotency-key:v1"
            .getBytes(StandardCharsets.UTF_8);
    private static final byte[] REQUEST_DOMAIN = "lifeos:goal-mutation:request:v1"
            .getBytes(StandardCharsets.UTF_8);

    private GoalMutationFingerprint() {
    }

    static String keyHash(String idempotencyKey) {
        return digest(KEY_DOMAIN, idempotencyKey);
    }

    static String requestFingerprint(
            UUID goalId, GoalMutationOperation operation, long expectedVersion, String title) {
        return requestFingerprint(goalId, operation, expectedVersion, title, 3, null);
    }

    static String requestFingerprint(
            UUID goalId,
            GoalMutationOperation operation,
            long expectedVersion,
            String title,
            int priority,
            java.time.Instant dueAt) {
        Objects.requireNonNull(goalId, "goalId must not be null");
        Objects.requireNonNull(operation, "operation must not be null");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(REQUEST_DOMAIN);
            digest.update((byte) 0);
            updateField(digest, goalId.toString());
            updateField(digest, operation.name());
            updateField(digest, Long.toString(expectedVersion));
            updateField(digest, title == null ? "" : title);
            updateField(digest, title == null ? "absent" : "present");
            updateField(digest, Integer.toString(priority));
            updateField(digest, dueAt == null ? "" : dueAt.toString());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    private static String digest(byte[] domain, String value) {
        Objects.requireNonNull(value, "value must not be null");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(domain);
            digest.update((byte) 0);
            updateField(digest, value);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    private static void updateField(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
