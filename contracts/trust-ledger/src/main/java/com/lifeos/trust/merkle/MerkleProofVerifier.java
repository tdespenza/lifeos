package com.lifeos.trust.merkle;

import com.lifeos.trust.crypto.Hash32;

/** Stateless verifier for the documented domain-separated Merkle proof format. */
public final class MerkleProofVerifier {

    private MerkleProofVerifier() {
    }

    /**
     * Returns true only when the proof's original document digest reconstructs the supplied root.
     * A malformed/tampered but structurally representable proof deterministically returns false.
     */
    public static boolean verifies(MerkleProof proof, Hash32 expectedRoot) {
        if (proof == null || expectedRoot == null) {
            return false;
        }
        int leafIndex = proof.leafIndex();
        Hash32 current = MerkleTree.leafHash(proof.documentDigest());
        for (MerkleProofStep step : proof.steps()) {
            boolean currentIsLeft = (leafIndex & 1) == 0;
            MerkleSiblingSide expectedSiblingSide = currentIsLeft
                    ? MerkleSiblingSide.RIGHT
                    : MerkleSiblingSide.LEFT;
            if (step.siblingSide() != expectedSiblingSide) {
                return false;
            }
            current = step.siblingSide() == MerkleSiblingSide.LEFT
                    ? MerkleTree.nodeHash(step.sibling(), current)
                    : MerkleTree.nodeHash(current, step.sibling());
            leafIndex >>>= 1;
        }
        return leafIndex == 0 && current.equals(expectedRoot);
    }
}
