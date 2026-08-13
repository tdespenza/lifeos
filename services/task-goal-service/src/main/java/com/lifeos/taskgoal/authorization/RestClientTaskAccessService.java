package com.lifeos.taskgoal.authorization;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Fail-closed REST adapter for the identity service's validation and authorization contracts.
 * No inbound bearer value is retained after the validation call or written to a log/error.
 */
@Component
public class RestClientTaskAccessService implements TaskAccessService {

    private static final String VALIDATE_PATH = "/api/v1/auth/validate";
    private static final String DECISION_PATH = "/api/v1/internal/authorization/decisions";
    private static final String WORKLOAD_IDENTITY_HEADER = "X-LifeOS-Workload-Identity";
    private static final String WORKLOAD_TOKEN_HEADER = "X-LifeOS-Workload-Token";
    private static final Set<String> DENY_REASON_CODES = Set.of(
            "MALFORMED_REQUEST",
            "STALE_SUBJECT",
            "POLICY_VERSION_MISMATCH",
            "UNSUPPORTED_ACTION",
            "MISSING_ROLE",
            "TENANT_MISMATCH",
            "OWNER_MISMATCH",
            "POLICY_UNAVAILABLE");

    private final RestClient restClient;
    private final TaskGoalIdentityProperties properties;

    public RestClientTaskAccessService(TaskGoalIdentityProperties properties) {
        this(buildRestClient(properties), properties);
    }

    RestClientTaskAccessService(RestClient restClient, TaskGoalIdentityProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public TaskSubject authenticate(String authorizationHeader) {
        String token = extractBearerToken(authorizationHeader);
        try {
            ValidatedSubjectResponse response = restClient.get()
                    .uri(VALIDATE_PATH)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .header(WORKLOAD_IDENTITY_HEADER, properties.getWorkloadIdentity())
                    .header(WORKLOAD_TOKEN_HEADER, properties.getWorkloadToken())
                    .retrieve()
                    .body(ValidatedSubjectResponse.class);
            return toSubject(response);
        } catch (TaskAuthenticationFailure | TaskAuthorizationDependencyUnavailable exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 401) {
                throw new TaskAuthenticationFailure(exception);
            }
            throw new TaskAuthorizationDependencyUnavailable(exception);
        } catch (RuntimeException exception) {
            throw new TaskAuthorizationDependencyUnavailable(exception);
        }
    }

    @Override
    public void authorize(TaskSubject subject, String action, GoalAuthorizationResource resource) {
        try {
            AuthorizationDecisionResponse response = restClient.post()
                    .uri(DECISION_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(WORKLOAD_IDENTITY_HEADER, properties.getWorkloadIdentity())
                    .header(WORKLOAD_TOKEN_HEADER, properties.getWorkloadToken())
                    .body(new AuthorizationDecisionRequest(
                            subject.accountId(),
                            subject.sessionId(),
                            subject.accessTokenProof(),
                            action,
                            new DecisionResource(
                                    resource.resourceType(),
                                    resource.resourceId(),
                                    resource.tenantId(),
                                    resource.attributes()),
                            properties.getExpectedPolicyVersion()))
                    .retrieve()
                    .body(AuthorizationDecisionResponse.class);
            if (!isUsableDecision(response)) {
                throw new TaskAuthorizationDependencyUnavailable();
            }
            if ("POLICY_UNAVAILABLE".equals(response.reasonCode())) {
                throw new TaskAuthorizationDependencyUnavailable();
            }
            if (!"ALLOW".equals(response.outcome())) {
                throw new TaskAuthorizationDenied();
            }
        } catch (TaskAuthorizationDenied | TaskAuthorizationDependencyUnavailable exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new TaskAuthorizationDependencyUnavailable(exception);
        }
    }

    private static RestClient buildRestClient(TaskGoalIdentityProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getReadTimeout());
        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    private static String extractBearerToken(String authorizationHeader) {
        if (authorizationHeader == null
                || !authorizationHeader.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            throw new TaskAuthenticationFailure();
        }
        String token = authorizationHeader.substring("Bearer ".length()).trim();
        if (!StringUtils.hasText(token) || token.chars().anyMatch(Character::isWhitespace)) {
            throw new TaskAuthenticationFailure();
        }
        return token;
    }

    private static TaskSubject toSubject(ValidatedSubjectResponse response) {
        if (response == null
                || response.accountId() == null
                || response.sessionId() == null
                || !StringUtils.hasText(response.authenticationMethod())
                || !isFixedFormatAccessTokenProof(response.accessTokenProof())) {
            throw new TaskAuthorizationDependencyUnavailable();
        }
        try {
            return new TaskSubject(
                    response.accountId(),
                    response.sessionId(),
                    response.authenticationMethod(),
                    response.accessTokenProof());
        } catch (IllegalArgumentException exception) {
            throw new TaskAuthorizationDependencyUnavailable(exception);
        }
    }

    private boolean isUsableDecision(AuthorizationDecisionResponse response) {
        if (response == null
                || !("ALLOW".equals(response.outcome()) || "DENY".equals(response.outcome()))
                || !StringUtils.hasText(response.reasonCode())) {
            return false;
        }
        if ("DENY".equals(response.outcome())) {
            // A deterministic denial remains safe even when identity could not resolve a
            // policy/session expiry. In particular, STALE_SUBJECT is deliberately emitted with
            // policyVersion=unknown and expiresAt=now after durable session revalidation.
            // Requiring allow-only freshness fields here would turn a required deny into 503.
            return DENY_REASON_CODES.contains(response.reasonCode());
        }
        return "ALLOWED".equals(response.reasonCode())
                && properties.getExpectedPolicyVersion().equals(response.policyVersion())
                && response.expiresAt() != null
                && response.expiresAt().isAfter(Instant.now());
    }

    private static boolean isFixedFormatAccessTokenProof(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ValidatedSubjectResponse(
            UUID accountId, UUID sessionId, String authenticationMethod, String accessTokenProof) {

        @Override
        public String toString() {
            return "ValidatedSubjectResponse[accountId=" + accountId
                    + ", sessionId=" + sessionId
                    + ", authenticationMethod=" + authenticationMethod
                    + ", accessTokenProof=[redacted]]";
        }
    }

    record AuthorizationDecisionRequest(
            UUID subjectId,
            UUID sessionId,
            String accessTokenProof,
            String action,
            DecisionResource resource,
            String expectedPolicyVersion) {

        @Override
        public String toString() {
            return "AuthorizationDecisionRequest[subjectId=" + subjectId
                    + ", sessionId=" + sessionId
                    + ", accessTokenProof=[redacted]"
                    + ", action=" + action
                    + ", resource=" + resource
                    + ", expectedPolicyVersion=" + expectedPolicyVersion + ']';
        }
    }

    record DecisionResource(String resourceType, String resourceId, String tenantId, Map<String, String> attributes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AuthorizationDecisionResponse(String outcome, String reasonCode, String policyVersion, Instant expiresAt) {
    }
}
