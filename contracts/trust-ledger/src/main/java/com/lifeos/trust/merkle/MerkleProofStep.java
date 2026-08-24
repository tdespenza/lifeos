package com.lifeos.trust.merkle;

import com.lifeos.trust.crypto.Hash32;
import java.util.Objects;

/** One domain-separated sibling step in a Merkle inclusion proof. */
public record MerkleProofStep(Hash32 sibling, MerkleSiblingSide siblingSide) {

    public MerkleProofStep {
        Objects.requireNonNull(sibling, "sibling must not be null");
        Objects.requireNonNull(siblingSide, "siblingSide must not be null");
    }
}
