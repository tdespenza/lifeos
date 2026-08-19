package com.lifeos.documentvault.authorization;

import java.util.Objects;
import java.util.UUID;

/** Identity-validated subject facts used only for owner/tenant scope derivation. */
public record DocumentVaultSubject(UUID accountId, UUID sessionId, String authenticationMethod, String accessTokenProof) {

    public DocumentVaultSubject {
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (authenticationMethod == null || authenticationMethod.isBlank()) {
            throw new IllegalArgumentException("authenticationMethod must not be blank");
        }
        if (accessTokenProof == null || !accessTokenProof.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("accessTokenProof must be a fixed-format internal proof");
        }
    }

    /** The current platform tenancy model gives each account one personal tenant. */
    public String tenantId() {
        return accountId.toString();
    }

    @Override
    public String toString() {
        return "DocumentVaultSubject[redacted]";
    }
}
