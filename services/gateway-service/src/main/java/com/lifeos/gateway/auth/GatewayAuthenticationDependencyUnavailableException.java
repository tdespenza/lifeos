package com.lifeos.gateway.auth;

import java.util.Set;

/**
 * Raised when the identity authority cannot safely complete a protected-request validation.
 */
public class GatewayAuthenticationDependencyUnavailableException extends RuntimeException {

    /** Invalid or incomplete response from the identity validation authority. */
    public static final String REASON_INVALID_AUTHORITY_RESPONSE = "identity_response_invalid";

    /** Transport, timeout, or unexpected status failure from the identity authority. */
    public static final String REASON_IDENTITY_UNAVAILABLE = "identity_unavailable";

    /** Authentication validation bulkhead rejected work before an identity call began. */
    public static final String REASON_BULKHEAD_REJECTED = "identity_bulkhead_rejected";

    private static final Set<String> ALLOWED_REASON_CODES = Set.of(
            REASON_INVALID_AUTHORITY_RESPONSE, REASON_IDENTITY_UNAVAILABLE, REASON_BULKHEAD_REJECTED);

    private final String reasonCode;

    /**
     * Creates a client-safe dependency failure without exposing topology or exception text.
     */
    public GatewayAuthenticationDependencyUnavailableException() {
        this(REASON_INVALID_AUTHORITY_RESPONSE, null);
    }

    /**
     * Creates a client-safe dependency failure while retaining a non-client-visible cause.
     *
     * @param cause internal failure cause
     */
    public GatewayAuthenticationDependencyUnavailableException(Throwable cause) {
        this(REASON_IDENTITY_UNAVAILABLE, cause);
    }

    /**
     * Creates a client-safe dependency failure with a bounded internal reason code.
     *
     * @param reasonCode bounded internal diagnostic category
     */
    public GatewayAuthenticationDependencyUnavailableException(String reasonCode) {
        this(reasonCode, null);
    }

    private GatewayAuthenticationDependencyUnavailableException(String reasonCode, Throwable cause) {
        super(null, cause, false, false);
        if (!ALLOWED_REASON_CODES.contains(reasonCode)) {
            throw new IllegalArgumentException("unsupported authentication dependency reason");
        }
        this.reasonCode = reasonCode;
    }

    /**
     * Returns the bounded internal diagnostic category without exposing it in the HTTP response.
     *
     * @return internal reason code
     */
    public String reasonCode() {
        return reasonCode;
    }
}
