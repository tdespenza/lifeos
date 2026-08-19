package com.lifeos.trustledger.proof;

/** Durable state of a document proof command in the Trust Ledger boundary. */
public enum TrustDocumentProofState {
    PENDING_EXTERNAL_ANCHOR,
    SUBMITTING,
    SUBMITTED,
    CONFIRMED,
    FAILED
}
