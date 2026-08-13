package com.lifeos.taskgoal.authorization;

import java.util.Objects;
import java.util.UUID;

/** A subject whose bearer token has been validated by the identity service. */
public record TaskSubject(UUID accountId, UUID sessionId, String authenticationMethod) {

    public TaskSubject {
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (authenticationMethod == null || authenticationMethod.isBlank()) {
            throw new IllegalArgumentException("authenticationMethod must not be blank");
        }
    }

    /**
     * Goals use a one-account tenant boundary until multi-account tenants are introduced.
     *
     * @return stable tenant identifier derived only from the validated subject
     */
    public String tenantId() {
        return accountId.toString();
    }
}
