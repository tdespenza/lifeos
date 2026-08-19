package com.lifeos.trustledger.certificate;

/** Durable state for an owner-scoped completed-goal certificate. */
public enum TrustGoalCertificateState {
    PENDING_EXTERNAL_ANCHOR,
    SUBMITTING,
    CONFIRMED
}
