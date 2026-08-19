package com.lifeos.trust.merkle;

import com.lifeos.trust.crypto.Hash32;
import java.util.List;
import java.util.Objects;

/** Immutable bounded proof that one original document digest is included in a Merkle root. */
public record MerkleProof(int leafIndex, Hash32 documentDigest, List<MerkleProofStep> steps) {

    public static final int MAX_STEPS = 32;

    public MerkleProof {
        if (leafIndex < 0) {
            throw new IllegalArgumentException("leafIndex must not be negative");
        }
        Objects.requireNonNull(documentDigest, "documentDigest must not be null");
        if (steps == null || steps.size() > MAX_STEPS || steps.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("proof steps must be non-null and bounded");
        }
        steps = List.copyOf(steps);
    }
}
