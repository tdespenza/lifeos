package com.lifeos.trust.merkle;

import com.lifeos.trust.ProofInputException;
import com.lifeos.trust.crypto.DocumentHasher;
import com.lifeos.trust.crypto.Hash32;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Bounded deterministic binary Merkle tree over ordered, unique document digests.
 *
 * <p>Leaves are {@code SHA-256(0x00 || documentDigest)} and internal nodes are
 * {@code SHA-256(0x01 || left || right)}. At an odd level the final node is duplicated as its own
 * right sibling. The anchored root is {@code SHA-256(0x02 || leafCount || levelRoot)}, where
 * {@code leafCount} is a four-byte big-endian integer. These domain-separation, count-binding, and
 * odd-node rules are part of the public proof format; callers must not substitute raw concatenation
 * or unordered leaf sets.
 *
 * <p>The default build accepts at most {@value #DEFAULT_MAX_LEAVES} leaves. The overload with
 * {@code maxLeaves} accepts any positive caller-supplied upper bound, so callers must keep that
 * value bounded for their available memory. For {@code n} leaves, construction takes O(n) time and
 * retains O(n) hash references across all levels (the total level size is less than 2n); with a
 * caller-supplied limit, worst-case memory is therefore O(maxLeaves), plus object overhead. Full
 * levels are retained deliberately so proofs can be produced in O(log n) time without a second
 * pass or subtree recomputation; a root-only or streaming implementation would reduce retained
 * memory but would require recomputation or another source pass for proofs.
 */
public final class MerkleTree {

    public static final int DEFAULT_MAX_LEAVES = 10_000;
    private static final byte[] LEAF_DOMAIN = new byte[] {0};
    private static final byte[] NODE_DOMAIN = new byte[] {1};
    private static final byte[] ROOT_DOMAIN = new byte[] {2};

    private final List<Hash32> documentDigests;
    private final Map<Hash32, Integer> digestIndexes;
    private final List<List<Hash32>> levels;

    private MerkleTree(List<Hash32> documentDigests, List<List<Hash32>> levels) {
        this.documentDigests = List.copyOf(documentDigests);
        Map<Hash32, Integer> indexes = new HashMap<>(this.documentDigests.size());
        for (int index = 0; index < this.documentDigests.size(); index++) {
            indexes.put(this.documentDigests.get(index), index);
        }
        this.digestIndexes = Map.copyOf(indexes);
        this.levels = levels.stream().map(List::copyOf).toList();
    }

    /** Builds the standard tree for at most 10,000 unique ordered document digests. */
    public static MerkleTree build(List<Hash32> orderedDocumentDigests) {
        return build(orderedDocumentDigests, DEFAULT_MAX_LEAVES);
    }

    /**
     * Builds one tree with a caller-owned upper leaf limit.
     *
     * @param orderedDocumentDigests deterministic upload/batch order
     * @param maxLeaves positive upper bound
     * @return immutable Merkle tree and proof source
     */
    public static MerkleTree build(List<Hash32> orderedDocumentDigests, int maxLeaves) {
        if (orderedDocumentDigests == null || orderedDocumentDigests.isEmpty()) {
            throw new ProofInputException("at least one document digest is required");
        }
        if (maxLeaves < 1 || orderedDocumentDigests.size() > maxLeaves) {
            throw new ProofInputException("document digest batch exceeds the configured leaf limit");
        }
        if (orderedDocumentDigests.stream().anyMatch(Objects::isNull)) {
            throw new ProofInputException("document digest batch must not contain null");
        }
        Set<Hash32> unique = new LinkedHashSet<>(orderedDocumentDigests);
        if (unique.size() != orderedDocumentDigests.size()) {
            throw new ProofInputException("document digest batch must be unique");
        }

        List<List<Hash32>> levels = new ArrayList<>();
        List<Hash32> current = orderedDocumentDigests.stream().map(MerkleTree::leafHash).toList();
        levels.add(current);
        while (current.size() > 1) {
            List<Hash32> next = new ArrayList<>((current.size() + 1) / 2);
            for (int index = 0; index < current.size(); index += 2) {
                Hash32 left = current.get(index);
                Hash32 right = index + 1 < current.size() ? current.get(index + 1) : left;
                next.add(nodeHash(left, right));
            }
            current = List.copyOf(next);
            levels.add(current);
        }
        return new MerkleTree(orderedDocumentDigests, levels);
    }

    /** Returns the immutable leaf-count-bound root hash. */
    public Hash32 root() {
        return rootHash(levels.getLast().getFirst(), documentDigests.size());
    }

    /** Returns an inclusion proof for the original ordered leaf index. */
    public MerkleProof proofFor(int leafIndex) {
        if (leafIndex < 0 || leafIndex >= documentDigests.size()) {
            throw new ProofInputException("leaf index is outside this Merkle tree");
        }
        List<MerkleProofStep> steps = new ArrayList<>(levels.size() - 1);
        int index = leafIndex;
        for (int level = 0; level < levels.size() - 1; level++) {
            List<Hash32> nodes = levels.get(level);
            boolean currentIsLeft = index % 2 == 0;
            int siblingIndex = currentIsLeft ? index + 1 : index - 1;
            Hash32 sibling = siblingIndex < nodes.size() ? nodes.get(siblingIndex) : nodes.get(index);
            steps.add(new MerkleProofStep(sibling, currentIsLeft ? MerkleSiblingSide.RIGHT : MerkleSiblingSide.LEFT));
            index /= 2;
        }
        return new MerkleProof(leafIndex, documentDigests.size(), documentDigests.get(leafIndex), steps);
    }

    /** Returns an inclusion proof for a unique original digest. */
    public MerkleProof proofFor(Hash32 documentDigest) {
        Objects.requireNonNull(documentDigest, "documentDigest must not be null");
        Integer index = digestIndexes.get(documentDigest);
        if (index == null) {
            throw new ProofInputException("document digest is not present in this Merkle tree");
        }
        return proofFor(index);
    }

    static int expectedProofStepCount(int leafCount) {
        if (leafCount < 1) {
            throw new IllegalArgumentException("leafCount must be positive");
        }
        int steps = 0;
        for (int nodes = leafCount; nodes > 1; nodes = nodes / 2 + nodes % 2) {
            steps++;
        }
        return steps;
    }

    static Hash32 leafHash(Hash32 documentDigest) {
        return DocumentHasher.sha256(LEAF_DOMAIN, documentDigest, null);
    }

    static Hash32 nodeHash(Hash32 left, Hash32 right) {
        return DocumentHasher.sha256(NODE_DOMAIN, left, right);
    }

    static Hash32 rootHash(Hash32 levelRoot, int leafCount) {
        return DocumentHasher.sha256(ROOT_DOMAIN, leafCount, levelRoot);
    }
}
