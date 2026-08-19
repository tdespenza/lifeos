package com.lifeos.trustledger.anchor;

/** Durable state for a digest-only anchor that is not tied to a Document Vault request. */
public enum TrustDigestAnchorState {
    PENDING_EXTERNAL_ANCHOR,
    SUBMITTING,
    CONFIRMED,
    FAILED
}
