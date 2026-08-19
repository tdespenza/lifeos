package com.lifeos.taskgoal.task.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Domain-separated SHA-256 fingerprinting without retaining raw client keys or task titles. */
final class TaskCommandFingerprint {

    private static final byte[] KEY_DOMAIN = "lifeos:task:idempotency-key:v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] REQUEST_DOMAIN = "lifeos:task:command-request:v1".getBytes(StandardCharsets.UTF_8);

    private TaskCommandFingerprint() {
    }

    static String keyHash(String key) {
        return sha256(KEY_DOMAIN, key);
    }

    static String requestFingerprint(
            UUID taskId, TaskCommandOperation operation, Long expectedVersion, String title) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(operation, "operation must not be null");
        String canonicalTaskId = operation == TaskCommandOperation.CREATE ? "" : taskId.toString();
        String canonical = field(canonicalTaskId)
                + field(operation.name())
                + field(expectedVersion == null ? "" : expectedVersion.toString())
                + field(title == null ? "" : title);
        return sha256(REQUEST_DOMAIN, canonical);
    }

    static String requestFingerprint(
            UUID taskId,
            TaskCommandOperation operation,
            Long expectedVersion,
            String title,
            int priority,
            java.time.Instant dueAt) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(operation, "operation must not be null");
        // CREATE reserves a fresh identifier on each HTTP submission. Its exact replay identity
        // therefore binds the decoded create payload and operation, not that transient proposal.
        String canonicalTaskId = operation == TaskCommandOperation.CREATE ? "" : taskId.toString();
        String canonical = field(canonicalTaskId)
                + field(operation.name())
                + field(expectedVersion == null ? "" : expectedVersion.toString())
                + field(title == null ? "" : title)
                + field(Integer.toString(priority))
                + field(dueAt == null ? "" : dueAt.toString());
        return sha256(REQUEST_DOMAIN, canonical);
    }

    /** Length-prefixing keeps the canonical byte sequence unambiguous for arbitrary task titles. */
    private static String field(String value) {
        return value.length() + ":" + value;
    }

    private static String sha256(byte[] domain, String value) {
        Objects.requireNonNull(value, "value must not be null");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(domain);
            digest.update((byte) 0);
            digest.update(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }
}
