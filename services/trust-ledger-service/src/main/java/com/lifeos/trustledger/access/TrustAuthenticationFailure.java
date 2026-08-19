package com.lifeos.trustledger.access;

/** Caller bearer credential is absent, malformed, expired, or rejected by Identity. */
public class TrustAuthenticationFailure extends RuntimeException {

    public TrustAuthenticationFailure() {
        super("authentication required");
    }

    public TrustAuthenticationFailure(Throwable cause) {
        super("authentication required", cause);
    }
}
