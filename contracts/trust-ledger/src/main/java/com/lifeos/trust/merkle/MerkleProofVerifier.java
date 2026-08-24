package com.lifeos.trust.merkle;

import com.lifeos.trust.crypto.Hash32;

/** Stateless verifier for the documented domain-separated Merkle proof format. */
public final class MerkleProofVerifier {

    private MerkleProofVerifier() {
    }

    /** Returns true only when a count-bound proof reconstructs the supplied root. */
    public static boolean verifies(MerkleProof proof, Hash32 expectedRoot) {
        if (proof == null || expectedRoot == null) {
            return false;
        }
        int leafCount = proof.leafCount();
        int leafIndex = proof.leafIndex();
        if (leafIndex < 0
                || leafIndex >= leafCount
                || proof.steps().size() != MerkleTree.expectedProofStepCount(leafCount)) {
            return false;
        }
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
        return leafIndex == 0 && MerkleTree.rootHash(current, leafCount).equals(expectedRoot);
    }
}
