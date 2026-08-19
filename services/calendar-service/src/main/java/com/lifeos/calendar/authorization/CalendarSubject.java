package com.lifeos.calendar.authorization;

import java.util.Objects;
import java.util.UUID;

/** Authenticated subject returned only by Identity's workload-authenticated validation endpoint. */
public record CalendarSubject(UUID accountId, UUID sessionId, String authenticationMethod, String accessTokenProof) {

    public CalendarSubject {
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (authenticationMethod == null || authenticationMethod.isBlank()) {
            throw new IllegalArgumentException("authenticationMethod must not be blank");
        }
        if (accessTokenProof == null || !accessTokenProof.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("accessTokenProof must be a fixed-format internal proof");
        }
    }

    /** The current platform tenancy model assigns every account one personal tenant. */
    public String tenantId() {
        return accountId.toString();
    }

    @Override
    public String toString() {
        return "CalendarSubject[redacted]";
    }
}
