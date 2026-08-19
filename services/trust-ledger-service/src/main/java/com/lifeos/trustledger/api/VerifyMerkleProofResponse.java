package com.lifeos.trustledger.api;

/** A deterministic verification result; malformed input is rejected rather than treated as false. */
public record VerifyMerkleProofResponse(boolean valid) {
}
