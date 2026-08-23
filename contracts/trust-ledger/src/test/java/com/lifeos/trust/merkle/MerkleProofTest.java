package com.lifeos.trust.merkle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lifeos.trust.crypto.Hash32;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class MerkleProofTest {

    @Test
    void rejectsInvalidCountsIndicesShapesAndNullValues() {
        Hash32 digest = hash(1);
        MerkleProofStep step = new MerkleProofStep(hash(2), MerkleSiblingSide.RIGHT);

        assertThrows(NullPointerException.class, () -> new MerkleProofStep(null, MerkleSiblingSide.RIGHT));
        assertThrows(NullPointerException.class, () -> new MerkleProofStep(digest, null));
        assertThrows(IllegalArgumentException.class, () -> new MerkleProof(0, 0, digest, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new MerkleProof(-1, 1, digest, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new MerkleProof(1, 1, digest, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new MerkleProof(0, 2, digest, List.of()));
        assertThrows(NullPointerException.class, () -> new MerkleProof(0, 1, null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new MerkleProof(0, 1, digest, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new MerkleProof(0, 2, digest, Collections.singletonList(null)));

        List<MerkleProofStep> steps = new ArrayList<>(List.of(step));
        MerkleProof proof = new MerkleProof(0, 2, digest, steps);
        assertEquals(steps, proof.steps());
    }

    @Test
    void defensivelyCopiesSuppliedSteps() {
        Hash32 digest = hash(1);
        MerkleProofStep original = new MerkleProofStep(hash(2), MerkleSiblingSide.RIGHT);
        MerkleProofStep replacement = new MerkleProofStep(hash(3), MerkleSiblingSide.RIGHT);
        List<MerkleProofStep> steps = new ArrayList<>(List.of(original));

        MerkleProof proof = new MerkleProof(0, 2, digest, steps);
        steps.set(0, replacement);

        assertEquals(original, proof.steps().getFirst());
        assertThrows(UnsupportedOperationException.class, () -> proof.steps().set(0, replacement));
    }

    private static Hash32 hash(int seed) {
        byte[] bytes = new byte[Hash32.BYTE_LENGTH];
        bytes[Hash32.BYTE_LENGTH - 1] = (byte) seed;
        return new Hash32(bytes);
    }
}
