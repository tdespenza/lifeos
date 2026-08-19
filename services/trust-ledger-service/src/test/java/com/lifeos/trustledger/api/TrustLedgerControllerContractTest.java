package com.lifeos.trustledger.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifeos.trustledger.access.TrustAccessService;
import com.lifeos.trustledger.access.TrustAuthenticationFailure;
import com.lifeos.trustledger.access.TrustAuthorizationResource;
import com.lifeos.trustledger.access.TrustSubject;
import com.lifeos.trustledger.service.TrustProofService;
import com.lifeos.trustledger.anchor.TrustAnchorService;
import com.lifeos.trustledger.anchor.TrustAnchorResult;
import com.lifeos.trustledger.anchor.TrustDocumentProofVerificationRequest;
import com.lifeos.trustledger.anchor.TrustDocumentProofVerificationResponse;
import com.lifeos.trustledger.certificate.TrustGoalCertificateService;
import com.lifeos.trustledger.certificate.TrustGoalCertificateResponse;
import com.lifeos.trustledger.certificate.TrustGoalCertificateState;
import com.lifeos.trustledger.certificate.TrustGoalCertificateVerificationRequest;
import com.lifeos.trustledger.certificate.TrustGoalCertificateVerificationResponse;
import com.lifeos.trustledger.proof.TrustDocumentProofState;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Executable public HTTP contract for bounded proof operations and fail-closed access. */
@WebMvcTest(TrustLedgerController.class)
class TrustLedgerControllerContractTest {

    private static final String ACCESS_TOKEN_PROOF =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String FIRST_DIGEST =
            "1111111111111111111111111111111111111111111111111111111111111111";
    private static final String SECOND_DIGEST =
            "2222222222222222222222222222222222222222222222222222222222222222";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TrustAccessService accessService;

    @MockitoBean
    private TrustProofService proofService;

    @MockitoBean
    private TrustAnchorService anchorService;

    @MockitoBean
    private TrustGoalCertificateService goalCertificateService;

    private TrustSubject subject;

    @BeforeEach
    void setUp() {
        subject = new TrustSubject(UUID.randomUUID(), UUID.randomUUID(), "password", ACCESS_TOKEN_PROOF);
        when(accessService.authenticate(any())).thenReturn(subject);
    }

    @Test
    void documentProofStreamsOnlyTheUploadAndUsesTheExactV2Action() throws Exception {
        when(proofService.hashDocument(any(), eq("text/plain"), eq("integrity")))
                .thenReturn(new DocumentProofResponse("SHA-256", FIRST_DIGEST, 5));

        mockMvc.perform(multipart("/api/v1/trust/document-proofs")
                        .file(new MockMultipartFile("content", "note.txt", "text/plain", "hello".getBytes()))
                        .param("mediaType", "text/plain")
                        .param("proofPurpose", "integrity")
                        .header("Authorization", "Bearer contract-token"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Correlation-ID"))
                .andExpect(jsonPath("$.algorithm").value("SHA-256"))
                .andExpect(jsonPath("$.digest").value(FIRST_DIGEST))
                .andExpect(jsonPath("$.contentBytes").value(5));

        verify(accessService).authorize(
                subject, "trust:document-proof-create", TrustAuthorizationResource.forSubject(subject));
    }

    @Test
    void merkleProofsUseTheExactBatchActionAndStableJsonContract() throws Exception {
        when(proofService.buildMerkleProofs(java.util.List.of(FIRST_DIGEST, SECOND_DIGEST)))
                .thenReturn(new MerkleBatchResponse(
                        "SHA-256-MERKLE-v1", FIRST_DIGEST, java.util.List.of()));

        mockMvc.perform(post("/api/v1/trust/merkle-proofs")
                        .header("Authorization", "Bearer contract-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentDigests\":[\"" + FIRST_DIGEST + "\",\"" + SECOND_DIGEST + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.algorithm").value("SHA-256-MERKLE-v1"))
                .andExpect(jsonPath("$.root").value(FIRST_DIGEST));

        verify(accessService).authorize(
                subject, "trust:merkle-proof-create", TrustAuthorizationResource.forSubject(subject));
    }

    @Test
    void malformedDigestIsRejectedBeforeProofServiceWork() throws Exception {
        mockMvc.perform(post("/api/v1/trust/merkle-proofs")
                        .header("Authorization", "Bearer contract-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentDigests\":[\"not-a-digest\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PROOF_INPUT"));

        verifyNoInteractions(proofService);
    }

    @Test
    void absentBearerFailsClosedBeforeTheProofService() throws Exception {
        doThrow(new TrustAuthenticationFailure()).when(accessService).authenticate(isNull());

        mockMvc.perform(post("/api/v1/trust/merkle-proofs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentDigests\":[\"" + FIRST_DIGEST + "\"]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"));

        verifyNoInteractions(proofService);
    }

    @Test
    void anchorEndpointRequiresDurableKeyAndUsesTheExactV2Capability() throws Exception {
        UUID requestId = UUID.randomUUID();
        when(anchorService.anchor(eq(subject), eq(requestId), eq("anchor-key")))
                .thenReturn(new TrustAnchorResult(
                        requestId, TrustDocumentProofState.CONFIRMED,
                        "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 12L,
                        Instant.parse("2026-08-18T00:00:00Z")));

        mockMvc.perform(post("/api/v1/trust/document-proof-requests/{requestId}/anchor", requestId)
                        .header("Authorization", "Bearer contract-token")
                        .header("Idempotency-Key", "anchor-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CONFIRMED"))
                .andExpect(jsonPath("$.transactionHash").value(
                        "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
                .andExpect(jsonPath("$.blockNumber").value(12));

        verify(accessService).authorize(
                subject, "trust:anchor-create", TrustAuthorizationResource.forSubject(subject));
    }

    @Test
    void anchorStatusUsesCredentialVerificationCapabilityAndNeverRequiresAnIdempotencyKey() throws Exception {
        UUID requestId = UUID.randomUUID();
        when(anchorService.status(eq(subject), eq(requestId)))
                .thenReturn(new TrustAnchorResult(
                        requestId, TrustDocumentProofState.PENDING_EXTERNAL_ANCHOR, null, null,
                        Instant.parse("2026-08-18T00:00:00Z")));

        mockMvc.perform(get("/api/v1/trust/document-proof-requests/{requestId}/anchor", requestId)
                        .header("Authorization", "Bearer contract-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PENDING_EXTERNAL_ANCHOR"));

        verify(accessService).authorize(
                subject, "trust:credential-verify", TrustAuthorizationResource.forSubject(subject));
    }

    @Test
    void documentProofVerificationUsesCredentialCapability() throws Exception {
        UUID requestId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        when(anchorService.verify(
                        eq(subject),
                        eq(requestId),
                        eq(new TrustDocumentProofVerificationRequest(documentId, 3, FIRST_DIGEST))))
                .thenReturn(TrustDocumentProofVerificationResponse.validResult());

        mockMvc.perform(post("/api/v1/trust/document-proof-requests/{requestId}/verify", requestId)
                        .header("Authorization", "Bearer contract-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentId\":\"" + documentId + "\",\"documentVersion\":3,"
                                + "\"checksumSha256\":\"" + FIRST_DIGEST + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.result").value("VALID"));

        verify(accessService).authorize(
                subject, "trust:credential-verify", TrustAuthorizationResource.forSubject(subject));
    }

    @Test
    void goalCertificateRequiresKeyAndUsesTheExactGoalCapability() throws Exception {
        UUID goalId = UUID.randomUUID();
        UUID certificateId = UUID.randomUUID();
        when(goalCertificateService.issue(eq(subject), eq(goalId), eq("goal-certificate-key")))
                .thenReturn(new TrustGoalCertificateResponse(
                        certificateId,
                        goalId,
                        4,
                        Instant.parse("2026-08-18T12:00:00Z"),
                        FIRST_DIGEST,
                        TrustGoalCertificateState.PENDING_EXTERNAL_ANCHOR,
                        null,
                        null,
                        Instant.parse("2026-08-18T13:00:00Z")));

        mockMvc.perform(post("/api/v1/trust/goal-certificates")
                        .header("Authorization", "Bearer contract-token")
                        .header("Idempotency-Key", "goal-certificate-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goalId\":\"" + goalId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.certificateId").value(certificateId.toString()))
                .andExpect(jsonPath("$.state").value("PENDING_EXTERNAL_ANCHOR"))
                .andExpect(jsonPath("$.achievementDigestSha256").value(FIRST_DIGEST));

        verify(accessService).authorize(
                subject, "trust:goal-certificate-create", TrustAuthorizationResource.forSubject(subject));
    }

    @Test
    void goalCertificateVerificationUsesCredentialCapability() throws Exception {
        UUID certificateId = UUID.randomUUID();
        UUID goalId = UUID.randomUUID();
        Instant completedAt = Instant.parse("2026-08-18T12:00:00Z");
        when(goalCertificateService.verify(
                        eq(subject),
                        eq(certificateId),
                        eq(new TrustGoalCertificateVerificationRequest(goalId, 4, completedAt, FIRST_DIGEST))))
                .thenReturn(TrustGoalCertificateVerificationResponse.validResult());

        mockMvc.perform(post("/api/v1/trust/goal-certificates/{certificateId}/verify", certificateId)
                        .header("Authorization", "Bearer contract-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goalId\":\"" + goalId + "\",\"goalVersion\":4,"
                                + "\"completedAt\":\"2026-08-18T12:00:00Z\",\"achievementDigestSha256\":\""
                                + FIRST_DIGEST + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.result").value("VALID"));

        verify(accessService).authorize(
                subject, "trust:credential-verify", TrustAuthorizationResource.forSubject(subject));
    }
}
