package com.lifeos.taskgoal.goal.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Produces domain-separated SHA-256 digests without retaining client key or payload values. */
final class GoalCreationFingerprint {

    private static final byte[] KEY_DOMAIN = "lifeos:goal-create:idempotency-key:v1"
            .getBytes(StandardCharsets.UTF_8);
    private static final byte[] REQUEST_DOMAIN = "lifeos:goal-create:request:v1"
            .getBytes(StandardCharsets.UTF_8);

    private GoalCreationFingerprint() {
    }

    static String keyHash(String idempotencyKey) {
        return sha256(KEY_DOMAIN, idempotencyKey);
    }

    static String requestFingerprint(String title) {
        return requestFingerprint(title, 3, null);
    }

    static String requestFingerprint(String title, int priority, java.time.Instant dueAt) {
        return sha256(REQUEST_DOMAIN, field(title) + field(Integer.toString(priority))
                + field(dueAt == null ? "" : dueAt.toString()));
    }

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
