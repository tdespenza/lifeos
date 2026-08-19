package com.lifeos.trustledger.anchor;

public class TrustAnchorUnavailableException extends RuntimeException {

    public TrustAnchorUnavailableException() {
        super("external anchor is unavailable");
    }

    public TrustAnchorUnavailableException(Throwable cause) {
        super("external anchor is unavailable", cause);
    }
}
