package com.lifeos.documentvault.proof;

/** Durable proof lifecycle. FAILED is a compensating terminal state for exhausted publication. */
public enum DocumentProofRequestState {
    REQUESTED,
    FAILED
}
