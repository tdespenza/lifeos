package com.lifeos.trust.merkle;

import com.lifeos.trust.crypto.Hash32;
import java.util.List;
import java.util.Objects;

/** Immutable bounded proof that one original document digest is included in a Merkle root. */
public record MerkleProof(int leafIndex, int leafCount, Hash32 documentDigest, List<MerkleProofStep> steps) {

    public static final int MAX_STEPS = 32;

    public MerkleProof {
        if (leafCount < 1) {
            throw new IllegalArgumentException("leafCount must be positive");
        }
        if (leafIndex < 0 || leafIndex >= leafCount) {
            throw new IllegalArgumentException("leafIndex must be within the leaf count");
        }
        Objects.requireNonNull(documentDigest, "documentDigest must not be null");
        if (steps == null
                || steps.size() > MAX_STEPS
                || steps.size() != MerkleTree.expectedProofStepCount(leafCount)
                || steps.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("proof steps must match the bounded leaf-count shape");
        }
        steps = List.copyOf(steps);
    }
}
