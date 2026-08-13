package com.lifeos.identity.auth;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Verifies the authenticated internal JWT-validation adapter consumed by protected services. */
@ExtendWith(MockitoExtension.class)
class JwtValidationControllerTest {

    @Mock
    private JwtValidationService validationService;

    @Mock
    private InternalWorkloadRateLimiter workloadRateLimiter;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        IdentityAuthProperties properties = new IdentityAuthProperties();
        properties.getAuthorization().setWorkloadIdentities(Map.of(
                "task-goal-service", "test-only-task-goal-workload-secret"));
        mockMvc = MockMvcBuilders.standaloneSetup(new JwtValidationController(
                        validationService,
                        new InternalWorkloadIdentityVerifier(properties),
                        workloadRateLimiter))
                .setControllerAdvice(new AuthenticationExceptionHandler())
                .build();
    }

    @Test
    void returnsOnlyValidatedSubjectFactsToAuthenticatedWorkload() throws Exception {
        UUID accountId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(validationService.validate("signed-access-token"))
                .thenReturn(new AuthenticatedSubject(accountId, sessionId, "PASSWORD"));

        mockMvc.perform(get("/api/v1/auth/validate")
                        .header(InternalWorkloadIdentityVerifier.IDENTITY_HEADER, "task-goal-service")
                        .header(InternalWorkloadIdentityVerifier.TOKEN_HEADER,
                                "test-only-task-goal-workload-secret")
                        .header("Authorization", "Bearer signed-access-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(accountId.toString()))
                .andExpect(jsonPath("$.sessionId").value(sessionId.toString()))
                .andExpect(jsonPath("$.authenticationMethod").value("PASSWORD"));

        verify(validationService).validate("signed-access-token");
        verify(workloadRateLimiter).check("task-goal-service");
    }

    @Test
    void rejectsUnauthenticatedWorkloadBeforeRateLimitOrTokenProcessing() throws Exception {
        mockMvc.perform(get("/api/v1/auth/validate")
                        .header("Authorization", "Bearer signed-access-token"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(validationService, workloadRateLimiter);
    }

    @Test
    void rejectsMissingBearerTokenAfterWorkloadAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/auth/validate")
                        .header(InternalWorkloadIdentityVerifier.IDENTITY_HEADER, "task-goal-service")
                        .header(InternalWorkloadIdentityVerifier.TOKEN_HEADER,
                                "test-only-task-goal-workload-secret"))
                .andExpect(status().isUnauthorized());

        verify(validationService, never()).validate(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void mapsTheSeparateWorkloadBudgetToAGenericRetryableResponse() throws Exception {
        org.mockito.Mockito.doThrow(new InternalWorkloadRateLimitExceededException(60))
                .when(workloadRateLimiter)
                .check("task-goal-service");

        mockMvc.perform(get("/api/v1/auth/validate")
                        .header(InternalWorkloadIdentityVerifier.IDENTITY_HEADER, "task-goal-service")
                        .header(InternalWorkloadIdentityVerifier.TOKEN_HEADER,
                                "test-only-task-goal-workload-secret")
                        .header("Authorization", "Bearer signed-access-token"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"));

        verify(validationService, never()).validate(org.mockito.ArgumentMatchers.anyString());
    }
}
