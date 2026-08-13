package com.lifeos.identity.authorization;

import java.time.Instant;
import java.util.Objects;

/**
 * Deterministic authorization outcome.
 *
 * <p>Every field is present for both allow and deny results so transport adapters have no
 * ambiguity. {@code reasonCode} is {@code ALLOWED} for an allow and an
 * {@link AuthorizationDenyReason} enum name for a denial.
 *
 * @param outcome allow or deny
 * @param reasonCode bounded outcome classification
 * @param policyVersion evaluated policy version, or {@code unknown} before policy resolution
 * @param expiresAt no later than the durable session deadline
 */
public record AuthorizationDecision(
        DecisionOutcome outcome, String reasonCode, String policyVersion, Instant expiresAt) {

    /**
     * Validates the authority's internal response invariant.
     */
    public AuthorizationDecision {
        Objects.requireNonNull(outcome, "outcome must not be null");
        if (reasonCode == null || reasonCode.isBlank()) {
            throw new IllegalArgumentException("reasonCode must not be blank");
        }
        if (policyVersion == null || policyVersion.isBlank()) {
            throw new IllegalArgumentException("policyVersion must not be blank");
        }
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }

    /**
     * Creates an allow response.
     *
     * @param policyVersion evaluated policy version
     * @param expiresAt effective decision deadline
     * @return allow decision
     */
    public static AuthorizationDecision allow(String policyVersion, Instant expiresAt) {
        return new AuthorizationDecision(DecisionOutcome.ALLOW, "ALLOWED", policyVersion, expiresAt);
    }

    /**
     * Creates a deny response.
     *
     * @param reason bounded deny classification
     * @param policyVersion evaluated policy version or {@code unknown}
     * @param expiresAt effective decision deadline
     * @return deny decision
     */
    public static AuthorizationDecision deny(
            AuthorizationDenyReason reason, String policyVersion, Instant expiresAt) {
        return new AuthorizationDecision(DecisionOutcome.DENY, reason.name(), policyVersion, expiresAt);
    }
}
