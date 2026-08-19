package com.lifeos.trustledger.api;

import com.lifeos.trust.merkle.MerkleSiblingSide;

/** One sibling hash and position in the stable public proof format. */
public record MerkleProofStepResponse(String siblingDigest, MerkleSiblingSide siblingSide) {
}
