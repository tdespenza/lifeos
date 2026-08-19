package com.lifeos.trustledger.api;

import java.util.List;

/** Deterministic root and inclusion proofs for an ordered digest batch. */
public record MerkleBatchResponse(String algorithm, String root, List<MerkleProofResponse> proofs) {
}
