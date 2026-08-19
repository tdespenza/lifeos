package com.lifeos.trust.merkle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lifeos.trust.ProofInputException;
import com.lifeos.trust.crypto.Hash32;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MerkleTreeTest {

    private static final List<Hash32> DIGESTS = List.of(hash(1), hash(2), hash(3), hash(4), hash(5));

    @Test
    void verifiesEveryOddBatchLeafWithStableRootAndProofRules() {
        MerkleTree first = MerkleTree.build(DIGESTS);
        MerkleTree second = MerkleTree.build(DIGESTS);

        assertTrue(first.root().equals(second.root()));
        for (int index = 0; index < DIGESTS.size(); index++) {
            MerkleProof proof = first.proofFor(index);
            assertTrue(MerkleProofVerifier.verifies(proof, first.root()));
            assertTrue(MerkleProofVerifier.verifies(first.proofFor(DIGESTS.get(index)), first.root()));
        }
    }

    @Test
    void rejectsTamperedLeafSiblingAndRoot() {
        MerkleTree tree = MerkleTree.build(DIGESTS);
        MerkleProof original = tree.proofFor(2);
        MerkleProof alteredLeaf = new MerkleProof(original.leafIndex(), hash(99), original.steps());
        MerkleProofStep firstStep = original.steps().getFirst();
        List<MerkleProofStep> alteredSteps = new ArrayList<>(original.steps());
        alteredSteps.set(0, new MerkleProofStep(hash(98), firstStep.siblingSide()));
        MerkleProof alteredSibling = new MerkleProof(
                original.leafIndex(),
                original.documentDigest(),
                alteredSteps);

        assertFalse(MerkleProofVerifier.verifies(alteredLeaf, tree.root()));
        assertFalse(MerkleProofVerifier.verifies(alteredSibling, tree.root()));
        assertFalse(MerkleProofVerifier.verifies(original, hash(97)));

        MerkleProof alteredIndex = new MerkleProof(1, original.documentDigest(), original.steps());
        assertFalse(MerkleProofVerifier.verifies(alteredIndex, tree.root()));
    }

    @Test
    void rejectsEmptyDuplicateAndOversizedBatchesAndUnknownProofRequests() {
        assertThrows(ProofInputException.class, () -> MerkleTree.build(List.of()));
        assertThrows(ProofInputException.class, () -> MerkleTree.build(List.of(hash(1), hash(1))));
        assertThrows(ProofInputException.class, () -> MerkleTree.build(List.of(hash(1), hash(2)), 1));

        MerkleTree tree = MerkleTree.build(DIGESTS);
        assertThrows(ProofInputException.class, () -> tree.proofFor(-1));
        assertThrows(ProofInputException.class, () -> tree.proofFor(hash(99)));
    }

    private static Hash32 hash(int seed) {
        byte[] bytes = new byte[Hash32.BYTE_LENGTH];
        bytes[Hash32.BYTE_LENGTH - 1] = (byte) seed;
        return new Hash32(bytes);
    }
}
