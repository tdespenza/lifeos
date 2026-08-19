package com.lifeos.media.service;

/** Trust Ledger cannot safely decide or complete the session-summary anchor command. */
public class MediaTrustLedgerUnavailableException extends RuntimeException {

    public MediaTrustLedgerUnavailableException() {
        super("Trust Ledger is unavailable");
    }

    public MediaTrustLedgerUnavailableException(Throwable cause) {
        super("Trust Ledger is unavailable", cause);
    }
}
