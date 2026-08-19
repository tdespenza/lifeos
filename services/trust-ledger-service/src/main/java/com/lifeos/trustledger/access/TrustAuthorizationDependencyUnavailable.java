package com.lifeos.trustledger.access;

/** Identity validation or authorization cannot produce a usable, current decision. */
public class TrustAuthorizationDependencyUnavailable extends RuntimeException {

    public TrustAuthorizationDependencyUnavailable() {
        super("identity authorization unavailable");
    }

    public TrustAuthorizationDependencyUnavailable(Throwable cause) {
        super("identity authorization unavailable", cause);
    }
}
