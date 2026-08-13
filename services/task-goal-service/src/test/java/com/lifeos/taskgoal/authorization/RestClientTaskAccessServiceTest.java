package com.lifeos.taskgoal.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withTooManyRequests;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RestClientTaskAccessServiceTest {

    private static final String IDENTITY_URL = "https://identity.test";

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
                        {"accountId":"%s","sessionId":"%s","authenticationMethod":"password"}
                        """.formatted(accountId, sessionId),
                        MediaType.APPLICATION_JSON));

        TaskSubject subject = accessService.authenticate("Bearer raw-access-token");

        assertThat(subject.accountId()).isEqualTo(accountId);
        assertThat(subject.sessionId()).isEqualTo(sessionId);
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
        assertThatThrownBy(() -> accessService.authenticate("Bearer rejected"))
                .isInstanceOf(TaskAuthenticationFailure.class);
        identityServer.verify();

        identityServer.reset();
        identityServer.expect(requestTo(IDENTITY_URL + "/api/v1/auth/validate"))
                .andRespond(withTooManyRequests());
        assertThatThrownBy(() -> accessService.authenticate("Bearer rate-limited"))
                .isInstanceOf(TaskAuthorizationDependencyUnavailable.class);
        identityServer.verify();
    }

    @Test
    void decisionSendsTrustedFactsAndFailsClosedOnDenyOrServerFailure() {
        TaskSubject subject = new TaskSubject(UUID.randomUUID(), UUID.randomUUID(), "password");
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
        assertThatThrownBy(() -> accessService.authorize(subject, GoalAuthorizationActions.READ, resource))
                .isInstanceOf(TaskAuthorizationDependencyUnavailable.class);
        identityServer.verify();
    }

    @Test
    void policyUnavailableDecisionMapsToDependencyUnavailable() {
        TaskSubject subject = new TaskSubject(UUID.randomUUID(), UUID.randomUUID(), "password");
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
        TaskSubject subject = new TaskSubject(UUID.randomUUID(), UUID.randomUUID(), "password");
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
        TaskSubject subject = new TaskSubject(UUID.randomUUID(), UUID.randomUUID(), "password");
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
        TaskSubject subject = new TaskSubject(UUID.randomUUID(), UUID.randomUUID(), "password");
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
        TaskSubject subject = new TaskSubject(UUID.randomUUID(), UUID.randomUUID(), "password");
        GoalAuthorizationResource resource = GoalAuthorizationResource.forCollection(subject.tenantId());
        identityServer.expect(requestTo(IDENTITY_URL + "/api/v1/internal/authorization/decisions"))
                .andExpect(content().json("""
                        {
                          "subjectId":"%s",
                          "sessionId":"%s",
                          "action":"goal:list",
                          "resource":{
                            "resourceType":"goal",
                            "resourceId":null,
                            "tenantId":"%s",
                            "attributes":{}
                          },
                          "expectedPolicyVersion":"v1"
                        }
                        """.formatted(subject.accountId(), subject.sessionId(), subject.tenantId())))
                .andRespond(withSuccess(
                        """
                        {"outcome":"ALLOW","reasonCode":"ALLOWED","policyVersion":"v1","expiresAt":"2099-01-01T00:00:00Z"}
                        """,
                        MediaType.APPLICATION_JSON));

        accessService.authorize(subject, GoalAuthorizationActions.LIST, resource);

        identityServer.verify();
    }
}
