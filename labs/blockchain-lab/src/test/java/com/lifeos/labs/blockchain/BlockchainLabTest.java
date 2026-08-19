package com.lifeos.labs.blockchain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lifeos.trust.crypto.DocumentProof;
import com.lifeos.trust.merkle.MerkleTree;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class BlockchainLabTest {

    @Test
    void documentProofAndMerkleInclusionAreVerifiable() throws Exception {
        DocumentProof first = BlockchainLab.proof("first synthetic document");
        DocumentProof second = BlockchainLab.proof("second synthetic document");
        MerkleTree tree = MerkleTree.build(List.of(first.digest(), second.digest()));

        assertTrue(BlockchainLab.verifies(tree.root(), tree.proofFor(first.digest())));
        assertTrue(BlockchainLab.verifies(tree.root(), tree.proofFor(second.digest())));
        assertFalse(BlockchainLab.verifies(first.digest(), tree.proofFor(second.digest())));
    }

    @Test
    void consensusRequiresStrictMajority() {
        assertFalse(BlockchainLab.consensus(5, 2).committed());
        assertTrue(BlockchainLab.consensus(5, 3).committed());
    }

    @Test
    void bloomFilterFindsInsertedValuesWithoutClaimingNoFalsePositives() {
        BlockchainLab.BloomFilter filter = new BlockchainLab.BloomFilter(1_024);
        filter.add("tx-1");
        filter.add("tx-2");

        assertTrue(filter.mightContain("tx-1"));
        assertTrue(filter.mightContain("tx-2"));
    }

    @Test
    void localChainReceiptIndexAndCredentialVerificationRemainBounded() throws Exception {
        DocumentProof first = BlockchainLab.proof("first synthetic document");
        DocumentProof second = BlockchainLab.proof("second synthetic document");
        MerkleTree tree = MerkleTree.build(List.of(first.digest(), second.digest()));
        BlockchainLab.LocalChainClient chain = new BlockchainLab.LocalChainClient(4);
        BlockchainLab.AnchorReceipt receipt = chain.anchor(tree.root(), "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");

        assertEquals(receipt, chain.receipt(receipt.transactionId()).orElseThrow());
        BlockchainLab.Credential credential = new BlockchainLab.Credential(
                "subject-1", first.digest(), tree.proofFor(first.digest()));
        assertTrue(BlockchainLab.CredentialVerifier.verifies(credential, receipt.merkleRoot()));
        assertTrue(!BlockchainLab.CredentialVerifier.verifies(credential, second.digest()));
    }
}
