package com.lifeos.labs.blockchain;

import com.lifeos.trust.crypto.CanonicalDocumentMetadata;
import com.lifeos.trust.crypto.DocumentHasher;
import com.lifeos.trust.crypto.DocumentProof;
import com.lifeos.trust.crypto.Hash32;
import com.lifeos.trust.merkle.MerkleProof;
import com.lifeos.trust.merkle.MerkleProofVerifier;
import com.lifeos.trust.merkle.MerkleTree;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Executable local blockchain-learning primitives. No network, wallet, or private document bytes
 * leave this process; the consensus and Bloom examples model bounded control flow only.
 */
public final class BlockchainLab {

    private BlockchainLab() {}

    public static void main(String[] args) throws IOException {
        DocumentProof proof = proof("synthetic-document");
        MerkleTree tree = MerkleTree.build(List.of(proof.digest()));
        System.out.println("{\"documentDigest\":\"" + proof.digest() + "\",\"merkleRoot\":\""
                + tree.root() + "\",\"verified\":" + MerkleProofVerifier.verifies(tree.proofFor(0), tree.root())
                + "}");
    }

    public static DocumentProof proof(String syntheticContent) throws IOException {
        if (syntheticContent == null || syntheticContent.isBlank() || syntheticContent.length() > 1_000_000) {
            throw new IllegalArgumentException("synthetic content must be bounded and non-empty");
        }
        return DocumentHasher.hash(
                new ByteArrayInputStream(syntheticContent.getBytes(StandardCharsets.UTF_8)),
                new CanonicalDocumentMetadata("text/plain", "blockchain-lab"),
                1_000_000);
    }

    public static boolean verifies(Hash32 root, MerkleProof proof) {
        return MerkleProofVerifier.verifies(proof, root);
    }

    public record ConsensusResult(boolean committed, int votes, int nodeCount, int quorum) {}

    public static ConsensusResult consensus(int nodeCount, int votes) {
        if (nodeCount < 1 || nodeCount > 31 || votes < 0 || votes > nodeCount) {
            throw new IllegalArgumentException("consensus inputs are outside bounded limits");
        }
        int quorum = nodeCount / 2 + 1;
        return new ConsensusResult(votes >= quorum, votes, nodeCount, quorum);
    }

    public record AnchorReceipt(String transactionId, Hash32 merkleRoot, String metadataHash, int confirmations) {
        public AnchorReceipt {
            if (transactionId == null || !transactionId.matches("0x[a-f0-9]{16,64}") || merkleRoot == null
                    || metadataHash == null || !metadataHash.matches("[a-f0-9]{64}")
                    || confirmations < 0 || confirmations > 1_000) {
                throw new IllegalArgumentException("anchor receipt is invalid or unbounded");
            }
        }
    }

    /** In-memory chain-client boundary used to teach receipt confirmation without a live node. */
    public static final class LocalChainClient {
        private final TransactionIndex index;
        private long sequence;

        public LocalChainClient(int maximumReceipts) {
            index = new TransactionIndex(maximumReceipts);
        }

        public synchronized AnchorReceipt anchor(Hash32 root, String metadataHash) {
            if (root == null || metadataHash == null || !metadataHash.matches("[a-f0-9]{64}")) {
                throw new IllegalArgumentException("anchor input is invalid");
            }
            sequence++;
            String transactionId = String.format("0x%016x", sequence);
            AnchorReceipt receipt = new AnchorReceipt(transactionId, root, metadataHash, 1);
            index.put(receipt);
            return receipt;
        }

        public Optional<AnchorReceipt> receipt(String transactionId) {
            return index.find(transactionId);
        }
    }

    /** Bloom-assisted bounded transaction lookup; false positives are resolved by the map. */
    public static final class TransactionIndex {
        private final Map<String, AnchorReceipt> receipts = new LinkedHashMap<>();
        private final BloomFilter bloom;
        private final int capacity;

        public TransactionIndex(int capacity) {
            if (capacity < 1 || capacity > 10_000) {
                throw new IllegalArgumentException("transaction index capacity must be between 1 and 10000");
            }
            this.capacity = capacity;
            bloom = new BloomFilter(Math.max(64, capacity * 16));
        }

        public synchronized void put(AnchorReceipt receipt) {
            if (receipt == null) {
                throw new IllegalArgumentException("receipt must not be null");
            }
            if (!receipts.containsKey(receipt.transactionId()) && receipts.size() >= capacity) {
                throw new IllegalStateException("transaction index capacity exceeded");
            }
            receipts.put(receipt.transactionId(), receipt);
            bloom.add(receipt.transactionId());
        }

        public synchronized Optional<AnchorReceipt> find(String transactionId) {
            if (transactionId == null || transactionId.isBlank() || transactionId.length() > 66
                    || !bloom.mightContain(transactionId)) {
                return Optional.empty();
            }
            return Optional.ofNullable(receipts.get(transactionId));
        }
    }

    public record Credential(String subjectId, Hash32 documentDigest, MerkleProof proof) {
        public Credential {
            if (subjectId == null || !subjectId.matches("[A-Za-z0-9_-]{1,64}")
                    || documentDigest == null || proof == null) {
                throw new IllegalArgumentException("credential is invalid");
            }
        }
    }

    public static final class CredentialVerifier {
        private CredentialVerifier() {}

        public static boolean verifies(Credential credential, Hash32 anchoredRoot) {
            return credential != null && anchoredRoot != null
                    && MerkleProofVerifier.verifies(credential.proof(), anchoredRoot)
                    && credential.proof().documentDigest().equals(credential.documentDigest());
        }
    }

    /** Simple deterministic Bloom filter for bounded transaction-index lookup experiments. */
    public static final class BloomFilter {

        private final long[] words;
        private final int bitCount;

        public BloomFilter(int bitCount) {
            if (bitCount < 64 || bitCount > 1_000_000) {
                throw new IllegalArgumentException("Bloom filter size must be between 64 and 1000000 bits");
            }
            this.bitCount = bitCount;
            words = new long[(bitCount + Long.SIZE - 1) / Long.SIZE];
        }

        public void add(String value) {
            set(bit(value, 0));
            set(bit(value, 1));
            set(bit(value, 2));
        }

        public boolean mightContain(String value) {
            return isSet(bit(value, 0)) && isSet(bit(value, 1)) && isSet(bit(value, 2));
        }

        private int bit(String value, int round) {
            if (value == null || value.isBlank() || value.length() > 256) {
                throw new IllegalArgumentException("Bloom value is invalid");
            }
            long hash = value.hashCode() * 0x9E3779B97F4A7C15L + round * 0xBF58476D1CE4E5B9L;
            hash ^= hash >>> 30;
            return Math.floorMod((int) (hash ^ (hash >>> 32)), bitCount);
        }

        private void set(int bit) {
            words[bit / Long.SIZE] |= 1L << (bit % Long.SIZE);
        }

        private boolean isSet(int bit) {
            return (words[bit / Long.SIZE] & (1L << (bit % Long.SIZE))) != 0;
        }
    }
}
