package com.lifeos.media.service;

/** Trust Ledger rejected the workload-authenticated session-summary command. */
public class MediaTrustLedgerDeniedException extends RuntimeException {

    public MediaTrustLedgerDeniedException() {
        super("Trust Ledger denied the session-summary anchor");
    }

    public MediaTrustLedgerDeniedException(Throwable cause) {
        super("Trust Ledger denied the session-summary anchor", cause);
    }
}
