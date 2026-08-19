package com.lifeos.trustledger.api;

import com.lifeos.trustledger.access.TrustAccessService;
import com.lifeos.trustledger.access.TrustAuthorizationResource;
import com.lifeos.trustledger.access.TrustSubject;
import com.lifeos.trustledger.anchor.TrustAnchorResult;
import com.lifeos.trustledger.anchor.TrustAnchorService;
import com.lifeos.trustledger.anchor.TrustDocumentProofVerificationRequest;
import com.lifeos.trustledger.anchor.TrustDocumentProofVerificationResponse;
import com.lifeos.trustledger.certificate.TrustGoalCertificateResponse;
import com.lifeos.trustledger.certificate.TrustGoalCertificateService;
import com.lifeos.trustledger.certificate.TrustGoalCertificateVerificationRequest;
import com.lifeos.trustledger.certificate.TrustGoalCertificateVerificationResponse;
import com.lifeos.trustledger.service.TrustProofService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.multipart.MultipartFile;

/** Public, authenticated API for deterministic document and Merkle proof operations. */
@RestController
@Validated
public class TrustLedgerController {

    private static final String DOCUMENT_PROOF_CREATE = "trust:document-proof-create";
    private static final String MERKLE_PROOF_CREATE = "trust:merkle-proof-create";
    private static final String PROOF_VERIFY = "trust:proof-verify";

    private final TrustAccessService accessService;
    private final TrustProofService proofService;
    private final TrustAnchorService anchorService;
    private final TrustGoalCertificateService goalCertificateService;

    public TrustLedgerController(
            TrustAccessService accessService,
            TrustProofService proofService,
            TrustAnchorService anchorService,
            TrustGoalCertificateService goalCertificateService) {
        this.accessService = accessService;
        this.proofService = proofService;
        this.anchorService = anchorService;
        this.goalCertificateService = goalCertificateService;
    }

    /**
     * Streams a bounded multipart document into a canonical digest and returns no document content.
     * Pure computation has no server-side mutation, so a retry deterministically recomputes rather
     * than reserving an idempotency record; future anchoring endpoints are distinct and durable.
     */
    @PostMapping(value = "/api/v1/trust/document-proofs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DocumentProofResponse createDocumentProof(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestPart("content") MultipartFile content,
            @RequestParam("mediaType") @NotBlank String mediaType,
            @RequestParam("proofPurpose") @NotBlank String proofPurpose)
            throws IOException {
        authorize(authorizationHeader, DOCUMENT_PROOF_CREATE);
        return proofService.hashDocument(content, mediaType, proofPurpose);
    }

    /** Builds deterministic inclusion proofs for a bounded, ordered digest batch. */
    @PostMapping(value = "/api/v1/trust/merkle-proofs", consumes = MediaType.APPLICATION_JSON_VALUE)
    public MerkleBatchResponse createMerkleProofs(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @Valid @RequestBody MerkleBatchRequest request) {
        authorize(authorizationHeader, MERKLE_PROOF_CREATE);
        return proofService.buildMerkleProofs(request.documentDigests());
    }

    /** Verifies one bounded proof against a supplied root without contacting the ledger. */
    @PostMapping(value = "/api/v1/trust/merkle-proofs/verify", consumes = MediaType.APPLICATION_JSON_VALUE)
    public VerifyMerkleProofResponse verifyMerkleProof(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @Valid @RequestBody VerifyMerkleProofRequest request) {
        authorize(authorizationHeader, PROOF_VERIFY);
        return proofService.verifyMerkleProof(request);
    }

    /** Claims one durable proof request and submits only its digest to the opt-in Besu adapter. */
    @PostMapping("/api/v1/trust/document-proof-requests/{requestId}/anchor")
    public TrustAnchorResult anchorDocumentProof(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @org.springframework.web.bind.annotation.PathVariable UUID requestId) {
        TrustSubject subject = accessService.authenticate(authorizationHeader);
        accessService.authorize(subject, "trust:anchor-create", TrustAuthorizationResource.forSubject(subject));
        return anchorService.anchor(subject, requestId, idempotencyKey);
    }

    /** Returns owner-scoped anchor state for receipt/credential verification workflows. */
    @GetMapping("/api/v1/trust/document-proof-requests/{requestId}/anchor")
    public TrustAnchorResult anchorStatus(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @org.springframework.web.bind.annotation.PathVariable UUID requestId) {
        TrustSubject subject = accessService.authenticate(authorizationHeader);
        accessService.authorize(subject, "trust:credential-verify", TrustAuthorizationResource.forSubject(subject));
        return anchorService.status(subject, requestId);
    }

    /** Verifies immutable document facts against the owner-scoped receipt state. */
    @PostMapping("/api/v1/trust/document-proof-requests/{requestId}/verify")
    public TrustDocumentProofVerificationResponse verifyDocumentProof(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @org.springframework.web.bind.annotation.PathVariable UUID requestId,
            @Valid @RequestBody TrustDocumentProofVerificationRequest request) {
        TrustSubject subject = accessService.authenticate(authorizationHeader);
        accessService.authorize(subject, "trust:credential-verify", TrustAuthorizationResource.forSubject(subject));
        return anchorService.verify(subject, requestId, request);
    }

    /** Issues a digest-only certificate for a completed owner goal after Task/Goal revalidation. */
    @PostMapping("/api/v1/trust/goal-certificates")
    public TrustGoalCertificateResponse issueGoalCertificate(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody GoalCertificateRequest request) {
        TrustSubject subject = accessService.authenticate(authorizationHeader);
        accessService.authorize(subject, "trust:goal-certificate-create", TrustAuthorizationResource.forSubject(subject));
        return goalCertificateService.issue(subject, request.goalId(), idempotencyKey);
    }

    /** Returns only the caller's certificate digest and external anchor state. */
    @GetMapping("/api/v1/trust/goal-certificates/{certificateId}")
    public TrustGoalCertificateResponse goalCertificateStatus(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @org.springframework.web.bind.annotation.PathVariable UUID certificateId) {
        TrustSubject subject = accessService.authenticate(authorizationHeader);
        accessService.authorize(subject, "trust:credential-verify", TrustAuthorizationResource.forSubject(subject));
        return goalCertificateService.status(subject, certificateId);
    }

    /** Verifies the digest and receipt state without contacting a caller-selected chain endpoint. */
    @PostMapping("/api/v1/trust/goal-certificates/{certificateId}/verify")
    public TrustGoalCertificateVerificationResponse verifyGoalCertificate(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @org.springframework.web.bind.annotation.PathVariable UUID certificateId,
            @Valid @RequestBody TrustGoalCertificateVerificationRequest request) {
        TrustSubject subject = accessService.authenticate(authorizationHeader);
        accessService.authorize(subject, "trust:credential-verify", TrustAuthorizationResource.forSubject(subject));
        return goalCertificateService.verify(subject, certificateId, request);
    }

    private void authorize(String authorizationHeader, String action) {
        TrustSubject subject = accessService.authenticate(authorizationHeader);
        accessService.authorize(subject, action, TrustAuthorizationResource.forSubject(subject));
    }

    public record GoalCertificateRequest(@jakarta.validation.constraints.NotNull UUID goalId) {
    }
}
