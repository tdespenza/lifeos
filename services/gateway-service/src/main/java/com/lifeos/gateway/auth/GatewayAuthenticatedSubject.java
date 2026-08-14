package com.lifeos.gateway.auth;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Sanitized subject facts returned by the identity authority for one protected request.
 *
 * <p>The access-token proof returned by identity-service is deliberately not retained here. It
 * is an internal authorization-boundary value, not a gateway forwarding header or a public claim.
 */
public record GatewayAuthenticatedSubject(UUID accountId, UUID sessionId, String authenticationMethod) {

    /** Header carrying the validated account identifier to trusted downstream services. */
    public static final String ACCOUNT_ID_HEADER = "X-LifeOS-Authenticated-Account-Id";

    /** Header carrying the validated session identifier to trusted downstream services. */
    public static final String SESSION_ID_HEADER = "X-LifeOS-Authenticated-Session-Id";

    /** Header carrying the validated authentication method to trusted downstream services. */
    public static final String AUTHENTICATION_METHOD_HEADER = "X-LifeOS-Authentication-Method";

    private static final int MAX_AUTHENTICATION_METHOD_LENGTH = 64;
    private static final Pattern AUTHENTICATION_METHOD_PATTERN =
            Pattern.compile("[A-Za-z0-9_-]{1," + MAX_AUTHENTICATION_METHOD_LENGTH + "}");
    private static final String REDACTED_REPRESENTATION = "GatewayAuthenticatedSubject[redacted]";

    /**
     * Validates the bounded subject representation before it reaches a downstream request.
     */
    public GatewayAuthenticatedSubject {
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (authenticationMethod == null
                || !AUTHENTICATION_METHOD_PATTERN.matcher(authenticationMethod).matches()) {
            throw new IllegalArgumentException("authenticationMethod must be a bounded safe value");
        }
    }

    @Override
    public String toString() {
        return REDACTED_REPRESENTATION;
    }
}
