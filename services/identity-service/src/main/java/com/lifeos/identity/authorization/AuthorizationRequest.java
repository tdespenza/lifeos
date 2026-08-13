package com.lifeos.identity.authorization;

import java.util.UUID;

/**
 * Versioned request for one authorization decision.
 *
 * @param subjectId authenticated account identifier from the identity validation boundary
 * @param sessionId durable session identifier from the identity validation boundary
 * @param accessTokenProof opaque proof for the exact access token accepted by validation
 * @param action exact action identifier
 * @param resource trusted resource facts already loaded by the protected service
 * @param expectedPolicyVersion version understood by the caller
 */
public record AuthorizationRequest(
        UUID subjectId,
        UUID sessionId,
        String accessTokenProof,
        String action,
        AuthorizationResource resource,
        String expectedPolicyVersion) {

    @Override
    public String toString() {
        return "AuthorizationRequest[subjectId=" + subjectId
                + ", sessionId=" + sessionId
                + ", accessTokenProof=[redacted]"
                + ", action=" + action
                + ", resource=" + resource
                + ", expectedPolicyVersion=" + expectedPolicyVersion + ']';
    }
}
