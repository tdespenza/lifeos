package com.lifeos.identity.authorization;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifeos.identity.auth.AuthenticationExceptionHandler;
import com.lifeos.identity.auth.AuthorizationDependencyUnavailableException;
import com.lifeos.identity.auth.ClientAddressResolver;
import com.lifeos.identity.auth.InternalWorkloadAuthenticationException;
import com.lifeos.identity.auth.InternalWorkloadIdentityVerifier;
import com.lifeos.identity.auth.InternalWorkloadRateLimiter;
import com.lifeos.identity.auth.SecurityAuditEventType;
import com.lifeos.identity.auth.SecurityAuditService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** HTTP adapter coverage for workload authentication, audit, and safe outcome mapping. */
@ExtendWith(MockitoExtension.class)
class AuthorizationDecisionControllerTest {

    @Mock
    private AuthorizationDecisionService decisionService;

    @Mock
    private InternalWorkloadIdentityVerifier workloadIdentityVerifier;

    @Mock
    private InternalWorkloadRateLimiter workloadRateLimiter;

    @Mock
    private SecurityAuditService auditService;

    @Mock
    private ClientAddressResolver clientAddressResolver;

    @Mock
    private AuthorizationMetrics metrics;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AuthorizationDecisionController(
                        decisionService,
                        workloadIdentityVerifier,
                        workloadRateLimiter,
                        auditService,
                        clientAddressResolver,
                        metrics))
                .setControllerAdvice(new AuthenticationExceptionHandler())
                .build();
    }

    @Test
    void returnsAuditedBoundedAllowDecisionForAuthenticatedWorkload() throws Exception {
        UUID subjectId = UUID.randomUUID();
        authenticatedWorkload();
        auditableRequest();
        when(decisionService.decideForAudit(any(), anyString())).thenReturn(evaluation(
                AuthorizationDecision.allow("v1", Instant.parse("2026-08-13T12:05:00Z")), subjectId));

        mockMvc.perform(post("/api/v1/internal/authorization/decisions")
                        .header(InternalWorkloadIdentityVerifier.IDENTITY_HEADER, "task-goal-service")
                        .header(InternalWorkloadIdentityVerifier.TOKEN_HEADER, "test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(subjectId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("ALLOW"))
                .andExpect(jsonPath("$.reasonCode").value("ALLOWED"))
                .andExpect(jsonPath("$.policyVersion").value("v1"))
                .andExpect(jsonPath("$.subjectId").doesNotExist())
                .andExpect(jsonPath("$.verifiedSubjectId").doesNotExist());

        verify(metrics).record(any());
        verify(decisionService).decideForAudit(any(), org.mockito.ArgumentMatchers.eq("task-goal-service"));
        verify(auditService).recordAuthorizationOutcome(
                SecurityAuditEventType.AUTHORIZATION_ALLOWED,
                subjectId,
                "127.0.0.1",
                "ALLOWED");
    }

    @Test
    void returnsAndAuditsDeterministicDenialWithoutResourceDetails() throws Exception {
        UUID subjectId = UUID.randomUUID();
        UUID goalId = UUID.randomUUID();
        authenticatedWorkload();
        auditableRequest();
        when(decisionService.decideForAudit(any(), anyString())).thenReturn(evaluation(
                AuthorizationDecision.deny(
                        AuthorizationDenyReason.OWNER_MISMATCH,
                        "v1",
                        Instant.parse("2026-08-13T12:05:00Z")),
                subjectId));

        mockMvc.perform(post("/api/v1/internal/authorization/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(subjectId, goalId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DENY"))
                .andExpect(jsonPath("$.reasonCode").value("OWNER_MISMATCH"))
                .andExpect(content().string(not(containsString(goalId.toString()))));

        verify(metrics).record(any());
        verify(auditService).recordAuthorizationOutcome(
                SecurityAuditEventType.AUTHORIZATION_DENIED,
                subjectId,
                "127.0.0.1",
                "OWNER_MISMATCH");
    }

    @Test
    void rejectsUnauthenticatedWorkloadWithoutEvaluatingPolicy() throws Exception {
        doThrow(new InternalWorkloadAuthenticationException()).when(workloadIdentityVerifier).verify(any());

        mockMvc.perform(post("/api/v1/internal/authorization/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(UUID.randomUUID())))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Internal authorization request failed")));

        verifyNoInteractions(decisionService, metrics, workloadRateLimiter, auditService);
    }

    @Test
    void failsClosedWhenAuditPersistenceCannotRecordAnAllow() throws Exception {
        authenticatedWorkload();
        auditableRequest();
        when(decisionService.decideForAudit(any(), anyString())).thenReturn(evaluation(
                AuthorizationDecision.allow("v1", Instant.parse("2026-08-13T12:05:00Z")), UUID.randomUUID()));
        doThrow(new AuthorizationDependencyUnavailableException()).when(auditService)
                .recordAuthorizationOutcome(any(), any(), anyString(), anyString());

        mockMvc.perform(post("/api/v1/internal/authorization/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(UUID.randomUUID())))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Authorization is temporarily unavailable.")));
    }

    @Test
    void failsClosedWithAGenericResponseWhenAuditPersistenceCannotRecordADenial() throws Exception {
        authenticatedWorkload();
        auditableRequest();
        when(decisionService.decideForAudit(any(), anyString())).thenReturn(evaluation(
                AuthorizationDecision.deny(
                        AuthorizationDenyReason.OWNER_MISMATCH,
                        "v1",
                        Instant.parse("2026-08-13T12:05:00Z")),
                UUID.randomUUID()));
        doThrow(new IllegalStateException("audit persistence unavailable")).when(auditService)
                .recordAuthorizationOutcome(any(), any(), anyString(), anyString());

        mockMvc.perform(post("/api/v1/internal/authorization/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(UUID.randomUUID())))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string(containsString("Authorization is temporarily unavailable.")))
                .andExpect(content().string(not(containsString("audit persistence unavailable"))));
    }

    @Test
    void staleSubjectDenialIsAuditedWithoutCallerSuppliedSubjectAttribution() throws Exception {
        authenticatedWorkload();
        auditableRequest();
        when(decisionService.decideForAudit(any(), anyString())).thenReturn(evaluation(
                AuthorizationDecision.deny(
                        AuthorizationDenyReason.STALE_SUBJECT,
                        "unknown",
                        Instant.parse("2026-08-13T12:05:00Z")),
                null));

        mockMvc.perform(post("/api/v1/internal/authorization/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DENY"))
                .andExpect(jsonPath("$.reasonCode").value("STALE_SUBJECT"));

        verify(auditService).recordAuthorizationOutcome(
                SecurityAuditEventType.AUTHORIZATION_DENIED,
                null,
                "127.0.0.1",
                "STALE_SUBJECT");
    }

    private void authenticatedWorkload() {
        when(workloadIdentityVerifier.verify(any())).thenReturn("task-goal-service");
    }

    private void auditableRequest() {
        when(clientAddressResolver.resolve(any())).thenReturn("127.0.0.1");
    }

    private AuthorizationDecisionEvaluation evaluation(AuthorizationDecision decision, UUID verifiedSubjectId) {
        return new AuthorizationDecisionEvaluation(decision, verifiedSubjectId);
    }

    private String requestJson(UUID subjectId) {
        return requestJson(subjectId, UUID.randomUUID());
    }

    private String requestJson(UUID subjectId, UUID goalId) {
        UUID sessionId = UUID.randomUUID();
        return """
                {
                  "subjectId":"%s",
                  "sessionId":"%s",
                  "action":"goal:read",
                  "resource":{
                    "resourceType":"goal",
                    "resourceId":"%s",
                    "tenantId":"%s",
                    "attributes":{"ownerAccountId":"%s","resourceExists":"true"}
                  },
                  "expectedPolicyVersion":"v1"
                }
                """.formatted(subjectId, sessionId, goalId, subjectId, subjectId);
    }
}
