package com.lifeos.media.authorization;

import java.util.Objects;
import java.util.UUID;

/** Identity-validated caller facts; Media derives tenancy and never accepts it from a request. */
public record MediaSubject(UUID accountId, UUID sessionId, String authenticationMethod, String accessTokenProof) {

    public MediaSubject {
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (authenticationMethod == null || authenticationMethod.isBlank()) {
            throw new IllegalArgumentException("authenticationMethod must not be blank");
        }
        if (accessTokenProof == null || !accessTokenProof.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("accessTokenProof must be a fixed-format internal proof");
        }
    }

    public String tenantId() {
        return accountId.toString();
    }

    @Override
    public String toString() {
        return "MediaSubject[redacted]";
    }
}
