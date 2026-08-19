package com.lifeos.trustledger.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifeos.trustledger.access.TrustAccessService;
import com.lifeos.trustledger.access.TrustAuthorizationDenied;
import com.lifeos.trustledger.anchor.TrustDigestAnchorResult;
import com.lifeos.trustledger.anchor.TrustDigestAnchorService;
import com.lifeos.trustledger.anchor.TrustDigestAnchorState;
import com.lifeos.trustledger.config.TrustMediaAnchorProperties;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TrustSessionSummaryAnchorController.class)
class TrustSessionSummaryAnchorControllerTest {

    private static final String TOKEN = "media-workload-token";
    private static final String DIGEST = "a".repeat(64);
    private static final UUID SUBJECT_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final UUID ARTIFACT_ID = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TrustAccessService accessService;

    @MockitoBean
    private TrustDigestAnchorService anchorService;

    @MockitoBean
    private TrustMediaAnchorProperties properties;

    @BeforeEach
    void setUp() {
        when(properties.configured()).thenReturn(true);
        when(properties.getWorkloadIdentity()).thenReturn("media-service");
        when(properties.getWorkloadToken()).thenReturn(TOKEN);
    }

    @Test
    void acceptsOnlyTheConfiguredMediaWorkloadAndExactV2Action() throws Exception {
        when(anchorService.anchor(any(), eq("MEDIA_SESSION_SUMMARY"), eq(ARTIFACT_ID), eq(3L), eq(DIGEST), eq("key")))
                .thenReturn(new TrustDigestAnchorResult(
                        UUID.randomUUID(), "MEDIA_SESSION_SUMMARY", ARTIFACT_ID, 3L, DIGEST,
                        TrustDigestAnchorState.CONFIRMED, "0xabc", 12L, Instant.parse("2026-08-19T00:00:00Z")));

        mockMvc.perform(post(TrustSessionSummaryAnchorController.PATH)
                        .header("X-LifeOS-Workload-Identity", "media-service")
                        .header("X-LifeOS-Workload-Token", TOKEN)
                        .header("Idempotency-Key", "key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CONFIRMED"))
                .andExpect(jsonPath("$.digestSha256").value(DIGEST));

        verify(accessService).authorize(any(), eq("trust:session-summary-anchor"), any());
        verify(anchorService).anchor(any(), eq("MEDIA_SESSION_SUMMARY"), eq(ARTIFACT_ID), eq(3L), eq(DIGEST), eq("key"));
    }

    @Test
    void rejectsMissingOrMismatchedWorkloadBeforeIdentityOrAnchorWork() throws Exception {
        mockMvc.perform(post(TrustSessionSummaryAnchorController.PATH)
                        .header("X-LifeOS-Workload-Identity", "media-service")
                        .header("X-LifeOS-Workload-Token", "wrong")
                        .header("Idempotency-Key", "key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(accessService, anchorService);
    }

    @Test
    void identityDenialIsPropagatedWithoutCallingAnchor() throws Exception {
        doThrow(new TrustAuthorizationDenied()).when(accessService)
                .authorize(any(), eq("trust:session-summary-anchor"), any());

        mockMvc.perform(post(TrustSessionSummaryAnchorController.PATH)
                        .header("X-LifeOS-Workload-Identity", "media-service")
                        .header("X-LifeOS-Workload-Token", TOKEN)
                        .header("Idempotency-Key", "key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(anchorService);
    }

    private static String requestBody() {
        return "{\"subjectId\":\"" + SUBJECT_ID + "\",\"sessionId\":\"" + SESSION_ID
                + "\",\"authenticationMethod\":\"workload-proof\",\"accessTokenProof\":\""
                + DIGEST + "\",\"artifactId\":\"" + ARTIFACT_ID + "\",\"artifactVersion\":3,\"digestSha256\":\""
                + DIGEST + "\"}";
    }
}
