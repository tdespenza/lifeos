package com.lifeos.taskgoal.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withTooManyRequests;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

import java.lang.ScopedValue;
import java.util.UUID;
import com.lifeos.taskgoal.observability.RequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

class RestClientTaskAccessServiceTest {

    private static final String IDENTITY_URL = "https://identity.test";
    private static final String ACCESS_TOKEN_PROOF =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private MockRestServiceServer identityServer;
    private RestClientTaskAccessService accessService;
    private TaskGoalIdentityProperties properties;

    @BeforeEach
    void setUp() {
        properties = new TaskGoalIdentityProperties();
        properties.setBaseUrl(IDENTITY_URL);
        properties.setWorkloadIdentity("task-goal-service");
        properties.setWorkloadToken("test-workload-token");
        properties.setExpectedPolicyVersion("v1");
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.getBaseUrl());
        identityServer = MockRestServiceServer.bindTo(builder).build();
        accessService = new RestClientTaskAccessService(builder.build(), properties);
    }

    @Test
    void authenticateForwardsOnlyTheParsedBearerAndWorkloadIdentity() {
        UUID accountId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        identityServer.expect(requestTo(IDENTITY_URL + "/api/v1/auth/validate"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer raw-access-token"))
                .andExpect(header("X-LifeOS-Workload-Identity", "task-goal-service"))
                .andExpect(header("X-LifeOS-Workload-Token", "test-workload-token"))
                .andRespond(withSuccess(
                        """
                        {"accountId":"%s","sessionId":"%s","authenticationMethod":"password","accessTokenProof":"%s"}
                        """.formatted(accountId, sessionId, ACCESS_TOKEN_PROOF),
                        MediaType.APPLICATION_JSON));

        TaskSubject subject = accessService.authenticate("Bearer raw-access-token");

        assertThat(subject.accountId()).isEqualTo(accountId);
        assertThat(subject.sessionId()).isEqualTo(sessionId);
        assertThat(subject.accessTokenProof()).isEqualTo(ACCESS_TOKEN_PROOF);
        identityServer.verify();
    }

    @Test
    void authenticatePropagatesTheBoundCorrelationId() throws Exception {
        UUID accountId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        String correlationId = "11111111-1111-4111-8111-111111111111";
        identityServer.expect(requestTo(IDENTITY_URL + "/api/v1/auth/validate"))
                .andExpect(header("X-Correlation-ID", correlationId))
                .andRespond(withSuccess(
                        """
                        {"accountId":"%s","sessionId":"%s","authenticationMethod":"password","accessTokenProof":"%s"}
                        """.formatted(accountId, sessionId, ACCESS_TOKEN_PROOF),
                        MediaType.APPLICATION_JSON));

        TaskSubject subject = ScopedValue.where(RequestContext.CORRELATION_ID, correlationId)
                .call(() -> accessService.authenticate("Bearer raw-access-token"));

        assertThat(subject.accountId()).isEqualTo(accountId);
        identityServer.verify();
    }

    @Test
    void missingBearerFailsBeforeAnyIdentityRequest() {
        assertThatThrownBy(() -> accessService.authenticate(null)).isInstanceOf(TaskAuthenticationFailure.class);
        identityServer.verify();
    }

    @Test
    void rejectedBearerBecomesAuthenticationFailureWhileRateLimitIsUnavailable() {
        identityServer.expect(requestTo(IDENTITY_URL + "/api/v1/auth/validate"))
                .andRespond(withUnauthorizedRequest());
        TaskAuthenticationFailure rejectedBearer = catchThrowableOfType(
                () -> accessService.authenticate("Bearer rejected"), TaskAuthenticationFailure.class);
        assertThat(rejectedBearer.getCause()).isInstanceOf(RestClientResponseException.class);
        assertThat(rejectedBearer.getMessage()).isNull();
        identityServer.verify();

        identityServer.reset();
        identityServer.expect(requestTo(IDENTITY_URL + "/api/v1/auth/validate"))
                .andRespond(withTooManyRequests());
        TaskAuthorizationDependencyUnavailable rateLimited = catchThrowableOfType(
                () -> accessService.authenticate("Bearer rate-limited"),
                TaskAuthorizationDependencyUnavailable.class);
        assertThat(rateLimited.getCause()).isInstanceOf(RestClientResponseException.class);
        assertThat(rateLimited.getMessage()).isNull();
        identityServer.verify();
    }

    @Test
    void decisionSendsTrustedFactsAndFailsClosedOnDenyOrServerFailure() {
        TaskSubject subject = subject();
        GoalAuthorizationResource resource = GoalAuthorizationResource.forExistingGoal(
                UUID.randomUUID(), subject.accountId(), subject.tenantId());
        identityServer.expect(requestTo(IDENTITY_URL + "/api/v1/internal/authorization/decisions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-LifeOS-Workload-Identity", "task-goal-service"))
                .andExpect(header("X-LifeOS-Workload-Token", "test-workload-token"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "subjectId":"%s",
                          "sessionId":"%s",
                          "accessTokenProof":"%s",
                          "action":"goal:read",
                          "resource":{
                            "resourceType":"goal",
                            "resourceId":"%s",
                            "tenantId":"%s",
                            "attributes":{"ownerAccountId":"%s","resourceExists":"true"}
                          },
                          "expectedPolicyVersion":"v1"
                        }
                        """.formatted(
                        subject.accountId(),
                        subject.sessionId(),
                        subject.accessTokenProof(),
                        resource.resourceId(),
                        subject.tenantId(),
                        subject.accountId())))
                .andRespond(withSuccess(
                        """
                        {"outcome":"ALLOW","reasonCode":"ALLOWED","policyVersion":"v1","expiresAt":"2099-01-01T00:00:00Z"}
                        """,
                        MediaType.APPLICATION_JSON));

        accessService.authorize(subject, GoalAuthorizationActions.READ, resource);
        identityServer.verify();

        identityServer.reset();
        identityServer.expect(requestTo(IDENTITY_URL + "/api/v1/internal/authorization/decisions"))
                .andRespond(withSuccess(
                        """
                        {"outcome":"DENY","reasonCode":"OWNER_MISMATCH","policyVersion":"v1","expiresAt":"2099-01-01T00:00:00Z"}
                        """,
                        MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> accessService.authorize(subject, GoalAuthorizationActions.READ, resource))
                .isInstanceOf(TaskAuthorizationDenied.class);
        identityServer.verify();

        identityServer.reset();
        identityServer.expect(requestTo(IDENTITY_URL + "/api/v1/internal/authorization/decisions"))
                .andRespond(withServerError());
        TaskAuthorizationDependencyUnavailable unavailable = catchThrowableOfType(
                () -> accessService.authorize(subject, GoalAuthorizationActions.READ, resource),
                TaskAuthorizationDependencyUnavailable.class);
        assertThat(unavailable.getCause()).isInstanceOf(RestClientResponseException.class);
        assertThat(unavailable.getMessage()).isNull();
        identityServer.verify();
    }

    @Test
    void authorizePropagatesTheBoundCorrelationId() {
        TaskSubject subject = subject();
        GoalAuthorizationResource resource = GoalAuthorizationResource.forCollection(subject.tenantId());
        String correlationId = "11111111-1111-4111-8111-111111111111";
        identityServer.expect(requestTo(IDENTITY_URL + "/api/v1/internal/authorization/decisions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Correlation-ID", correlationId))
                .andRespond(withSuccess(
                        """
                        {"outcome":"ALLOW","reasonCode":"ALLOWED","policyVersion":"v1","expiresAt":"2099-01-01T00:00:00Z"}
                        """,
                        MediaType.APPLICATION_JSON));

        ScopedValue.where(RequestContext.CORRELATION_ID, correlationId)
                .run(() -> accessService.authorize(subject, GoalAuthorizationActions.LIST, resource));

        identityServer.verify();
    }

    @Test
    void boundaryExceptionsKeepCausesWithoutCopyingPotentialCredentialTextIntoTheirMessages() {
        RuntimeException cause = new RuntimeException("Bearer raw-access-token");

        TaskAuthenticationFailure authenticationFailure = new TaskAuthenticationFailure(cause);
        TaskAuthorizationDependencyUnavailable dependencyUnavailable =
                new TaskAuthorizationDependencyUnavailable(cause);

        assertThat(authenticationFailure.getCause()).isSameAs(cause);
        assertThat(authenticationFailure.getMessage()).isNull();
        assertThat(dependencyUnavailable.getCause()).isSameAs(cause);
        assertThat(dependencyUnavailable.getMessage()).isNull();
    }

    @Test
    void policyUnavailableDecisionMapsToDependencyUnavailable() {
        TaskSubject subject = subject();
        GoalAuthorizationResource resource = GoalAuthorizationResource.forCollection(subject.tenantId());
        identityServer.expect(requestTo(IDENTITY_URL + "/api/v1/internal/authorization/decisions"))
                .andRespond(withSuccess(
                        """
                        {"outcome":"DENY","reasonCode":"POLICY_UNAVAILABLE","policyVersion":"v1","expiresAt":"2099-01-01T00:00:00Z"}
                        """,
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> accessService.authorize(subject, GoalAuthorizationActions.LIST, resource))
                .isInstanceOf(TaskAuthorizationDependencyUnavailable.class);
        identityServer.verify();
    }

    @Test
    void staleSubjectDecisionWithUnknownPolicyAndImmediateExpiryRemainsAGenericDeny() {
        TaskSubject subject = subject();
        GoalAuthorizationResource resource = GoalAuthorizationResource.forCollection(subject.tenantId());
        identityServer.expect(requestTo(IDENTITY_URL + "/api/v1/internal/authorization/decisions"))
                .andRespond(withSuccess(
                        """
                        {"outcome":"DENY","reasonCode":"STALE_SUBJECT","policyVersion":"unknown","expiresAt":"2000-01-01T00:00:00Z"}
                        """,
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> accessService.authorize(subject, GoalAuthorizationActions.LIST, resource))
                .isInstanceOf(TaskAuthorizationDenied.class);
        identityServer.verify();
    }

    @Test
    void malformedDecisionOutcomeFailsClosedAsDependencyUnavailable() {
        TaskSubject subject = subject();
        GoalAuthorizationResource resource = GoalAuthorizationResource.forCollection(subject.tenantId());
        identityServer.expect(requestTo(IDENTITY_URL + "/api/v1/internal/authorization/decisions"))
                .andRespond(withSuccess(
                        """
                        {"outcome":"UNKNOWN","reasonCode":"UNKNOWN","policyVersion":"v1","expiresAt":"2099-01-01T00:00:00Z"}
                        """,
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> accessService.authorize(subject, GoalAuthorizationActions.LIST, resource))
                .isInstanceOf(TaskAuthorizationDependencyUnavailable.class);
        identityServer.verify();
    }

    @Test
    void acceptsAnAllowOnlyWhenItsReasonAndPolicyVersionMatchTheContract() {
        TaskSubject subject = subject();
        GoalAuthorizationResource resource = GoalAuthorizationResource.forCollection(subject.tenantId());
        identityServer.expect(requestTo(IDENTITY_URL + "/api/v1/internal/authorization/decisions"))
                .andRespond(withSuccess(
                        """
                        {"outcome":"ALLOW","reasonCode":"OWNER_MISMATCH","policyVersion":"v1","expiresAt":"2099-01-01T00:00:00Z"}
                        """,
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> accessService.authorize(subject, GoalAuthorizationActions.LIST, resource))
                .isInstanceOf(TaskAuthorizationDependencyUnavailable.class);
        identityServer.verify();

        identityServer.reset();
        identityServer.expect(requestTo(IDENTITY_URL + "/api/v1/internal/authorization/decisions"))
                .andRespond(withSuccess(
                        """
                        {"outcome":"ALLOW","reasonCode":"ALLOWED","policyVersion":"v2","expiresAt":"2099-01-01T00:00:00Z"}
                        """,
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> accessService.authorize(subject, GoalAuthorizationActions.LIST, resource))
                .isInstanceOf(TaskAuthorizationDependencyUnavailable.class);
        identityServer.verify();

        identityServer.reset();
        identityServer.expect(requestTo(IDENTITY_URL + "/api/v1/internal/authorization/decisions"))
                .andRespond(withSuccess(
                        """
                        {"outcome":"ALLOW","reasonCode":"ALLOWED","policyVersion":"v1","expiresAt":"2000-01-01T00:00:00Z"}
                        """,
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> accessService.authorize(subject, GoalAuthorizationActions.LIST, resource))
                .isInstanceOf(TaskAuthorizationDependencyUnavailable.class);
        identityServer.verify();
    }

    @Test
    void collectionDecisionSendsTenantScopeWithoutOwnerAttribute() {
        TaskSubject subject = subject();
        GoalAuthorizationResource resource = GoalAuthorizationResource.forCollection(subject.tenantId());
        identityServer.expect(requestTo(IDENTITY_URL + "/api/v1/internal/authorization/decisions"))
                .andExpect(content().json("""
                        {
                          "subjectId":"%s",
                          "sessionId":"%s",
                          "accessTokenProof":"%s",
                          "action":"goal:list",
                          "resource":{
                            "resourceType":"goal",
                            "resourceId":null,
                            "tenantId":"%s",
                            "attributes":{}
                          },
                          "expectedPolicyVersion":"v1"
                        }
                        """.formatted(
                        subject.accountId(), subject.sessionId(), subject.accessTokenProof(), subject.tenantId())))
                .andRespond(withSuccess(
                        """
                        {"outcome":"ALLOW","reasonCode":"ALLOWED","policyVersion":"v1","expiresAt":"2099-01-01T00:00:00Z"}
                        """,
                        MediaType.APPLICATION_JSON));

        accessService.authorize(subject, GoalAuthorizationActions.LIST, resource);

        identityServer.verify();
    }

    private static TaskSubject subject() {
        return new TaskSubject(UUID.randomUUID(), UUID.randomUUID(), "password", ACCESS_TOKEN_PROOF);
    }
}
