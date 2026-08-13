package com.lifeos.taskgoal.authorization;

import java.util.Objects;
import java.util.UUID;

/**
 * A subject whose bearer token has been validated by the identity service.
 *
 * <p>{@code accessTokenProof} is opaque internal state used only to bind the following
 * authorization decision to the exact token that the workload-authenticated validation call
 * accepted. It is not exposed through the public Task/Goal API.
 */
public record TaskSubject(UUID accountId, UUID sessionId, String authenticationMethod, String accessTokenProof) {

    public TaskSubject {
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (authenticationMethod == null || authenticationMethod.isBlank()) {
            throw new IllegalArgumentException("authenticationMethod must not be blank");
        }
        if (accessTokenProof == null || !accessTokenProof.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("accessTokenProof must be a fixed-format internal proof");
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

    @Override
    public String toString() {
        return "TaskSubject[accountId=" + accountId
                + ", sessionId=" + sessionId
                + ", authenticationMethod=" + authenticationMethod
                + ", accessTokenProof=[redacted]]";
    }
}
