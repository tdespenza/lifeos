package com.lifeos.trustledger.access;

/** Identity evaluated the exact proof action and denied it. */
public class TrustAuthorizationDenied extends RuntimeException {

    public TrustAuthorizationDenied() {
        super("authorization denied");
    }
}
