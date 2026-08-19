package com.lifeos.trustledger.api;

import java.util.List;

/** One original digest and its ordered sibling path. */
public record MerkleProofResponse(int leafIndex, String documentDigest, List<MerkleProofStepResponse> steps) {
}
