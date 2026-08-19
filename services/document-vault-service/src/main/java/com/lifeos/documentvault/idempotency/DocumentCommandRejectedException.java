package com.lifeos.documentvault.idempotency;

/** Marker for deterministic request rejection after a reservation; the pending row is removable. */
public class DocumentCommandRejectedException extends RuntimeException {

    public DocumentCommandRejectedException() {
        super(null, null, false, false);
    }
}
