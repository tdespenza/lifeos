package com.lifeos.trustledger.service;

import com.lifeos.trust.ProofInputException;
import com.lifeos.trust.crypto.CanonicalDocumentMetadata;
import com.lifeos.trust.crypto.DocumentHasher;
import com.lifeos.trust.crypto.DocumentProof;
import com.lifeos.trust.crypto.Hash32;
import com.lifeos.trust.merkle.MerkleProof;
import com.lifeos.trust.merkle.MerkleProofVerifier;
import com.lifeos.trust.merkle.MerkleProofStep;
import com.lifeos.trust.merkle.MerkleTree;
import com.lifeos.trustledger.api.DocumentProofResponse;
import com.lifeos.trustledger.api.MerkleBatchResponse;
import com.lifeos.trustledger.api.MerkleProofResponse;
import com.lifeos.trustledger.api.MerkleProofStepRequest;
import com.lifeos.trustledger.api.MerkleProofStepResponse;
import com.lifeos.trustledger.api.VerifyMerkleProofRequest;
import com.lifeos.trustledger.api.VerifyMerkleProofResponse;
import com.lifeos.trustledger.config.TrustLedgerServiceProperties;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Stateless façade around the Trust Ledger's bounded, domain-separated cryptographic primitives.
 *
 * <p>Document content is only streamed from the multipart boundary into {@link DocumentHasher};
 * this service retains neither bytes nor a document reference. Merkle generation takes an ordered
 * digest batch and returns the documented proof format without attempting a ledger anchor.
 */
@Service
public class TrustProofService {

    public static final String MERKLE_ALGORITHM = "SHA-256-MERKLE-v1";

    private final TrustLedgerServiceProperties properties;
    private final TrustProofMetrics metrics;

    public TrustProofService(TrustLedgerServiceProperties properties, TrustProofMetrics metrics) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
    }

    /** Streams one upload under a configured bound and returns only non-content proof data. */
    public DocumentProofResponse hashDocument(MultipartFile content, String mediaType, String proofPurpose) throws IOException {
        Objects.requireNonNull(content, "content must not be null");
        Timer.Sample sample = metrics.start();
        String outcome = "success";
        try {
            if (content.getSize() > properties.getMaxDocumentBytes()) {
                throw new ProofInputException("document exceeds the configured content limit");
            }
            CanonicalDocumentMetadata metadata = new CanonicalDocumentMetadata(mediaType, proofPurpose);
            try (InputStream stream = content.getInputStream()) {
                DocumentProof proof = DocumentHasher.hash(stream, metadata, properties.getMaxDocumentBytes());
                return new DocumentProofResponse(proof.algorithm(), proof.digest().toHex(), proof.contentBytes());
            }
        } catch (IllegalArgumentException exception) {
            outcome = "invalid";
            throw exception;
        } catch (IOException exception) {
            outcome = "unreadable";
            throw exception;
        } finally {
            metrics.stop(sample, "document_hash", outcome);
        }
    }

    /** Builds one deterministic Merkle root and one proof for every ordered input digest. */
    public MerkleBatchResponse buildMerkleProofs(List<String> digestHexes) {
        Timer.Sample sample = metrics.start();
        String outcome = "success";
        try {
            List<Hash32> digests = Objects.requireNonNull(digestHexes, "digestHexes must not be null")
                    .stream()
                    .map(Hash32::fromHex)
                    .toList();
            MerkleTree tree = MerkleTree.build(digests, properties.getMaxMerkleLeaves());
            List<MerkleProofResponse> proofs = java.util.stream.IntStream.range(0, digests.size())
                    .mapToObj(index -> toResponse(tree.proofFor(index)))
                    .toList();
            return new MerkleBatchResponse(MERKLE_ALGORITHM, tree.root().toHex(), proofs);
        } catch (IllegalArgumentException exception) {
            outcome = "invalid";
            throw exception;
        } finally {
            metrics.stop(sample, "merkle_build", outcome);
        }
    }

    /** Reconstructs a root from a bounded client-supplied proof without allocating a tree. */
    public VerifyMerkleProofResponse verifyMerkleProof(VerifyMerkleProofRequest request) {
        Timer.Sample sample = metrics.start();
        String outcome = "success";
        try {
            Objects.requireNonNull(request, "request must not be null");
            MerkleProof proof = new MerkleProof(
                    request.leafIndex(),
                    Hash32.fromHex(request.documentDigest()),
                    request.steps().stream().map(TrustProofService::toProofStep).toList());
            boolean valid = MerkleProofVerifier.verifies(proof, Hash32.fromHex(request.root()));
            return new VerifyMerkleProofResponse(valid);
        } catch (IllegalArgumentException exception) {
            outcome = "invalid";
            throw exception;
        } finally {
            metrics.stop(sample, "merkle_verify", outcome);
        }
    }

    private static MerkleProofResponse toResponse(MerkleProof proof) {
        return new MerkleProofResponse(
                proof.leafIndex(),
                proof.documentDigest().toHex(),
                proof.steps().stream()
                        .map(step -> new MerkleProofStepResponse(step.sibling().toHex(), step.siblingSide()))
                        .toList());
    }

    private static MerkleProofStep toProofStep(MerkleProofStepRequest step) {
        return new MerkleProofStep(Hash32.fromHex(step.siblingDigest()), step.siblingSide());
    }
}
