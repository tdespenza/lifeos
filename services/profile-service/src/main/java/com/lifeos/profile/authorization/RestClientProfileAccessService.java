package com.lifeos.profile.authorization;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.lifeos.profile.observability.RequestContext;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Fail-closed, bounded REST adapter for Identity validation and authorization. It retains neither
 * an inbound bearer nor the workload secret after a call and never includes either in exceptions.
 */
@Component
public class RestClientProfileAccessService implements ProfileAccessService {

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
    private final ProfileIdentityProperties properties;
    private final Semaphore outboundPermits;

    @Autowired
    public RestClientProfileAccessService(RestClient.Builder restClientBuilder, ProfileIdentityProperties properties) {
        this(buildRestClient(restClientBuilder, properties), properties);
    }

    RestClientProfileAccessService(RestClient restClient, ProfileIdentityProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
        outboundPermits = new Semaphore(properties.getMaxConcurrentRequests(), true);
    }

    @Override
    public ProfileSubject authenticate(String authorizationHeader) {
        String token = extractBearerToken(authorizationHeader);
        try {
            return withOutboundPermit(() -> {
                RestClient.RequestHeadersSpec<?> requestSpec = restClient.get()
                        .uri(VALIDATE_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(WORKLOAD_IDENTITY_HEADER, properties.getWorkloadIdentity())
                        .header(WORKLOAD_TOKEN_HEADER, properties.getWorkloadToken());
                addCorrelationHeader(requestSpec);
                return toSubject(requestSpec.retrieve().body(ValidatedSubjectResponse.class));
            });
        } catch (ProfileAuthenticationFailure | ProfileAuthorizationDependencyUnavailable exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 401) {
                throw new ProfileAuthenticationFailure(exception);
            }
            throw new ProfileAuthorizationDependencyUnavailable(exception);
        } catch (RuntimeException exception) {
            throw new ProfileAuthorizationDependencyUnavailable(exception);
        }
    }

    @Override
    public void authorize(ProfileSubject subject, String action, ProfileAuthorizationResource resource) {
        try {
            withOutboundPermit(() -> {
                RestClient.RequestBodySpec requestSpec = restClient.post()
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
                                properties.getExpectedPolicyVersion()));
                addCorrelationHeader(requestSpec);
                AuthorizationDecisionResponse response = requestSpec.retrieve()
                        .body(AuthorizationDecisionResponse.class);
                if (!isUsableDecision(response)) {
                    throw new ProfileAuthorizationDependencyUnavailable();
                }
                if ("POLICY_UNAVAILABLE".equals(response.reasonCode())) {
                    throw new ProfileAuthorizationDependencyUnavailable();
                }
                if (!"ALLOW".equals(response.outcome())) {
                    throw new ProfileAuthorizationDenied();
                }
                return null;
            });
        } catch (ProfileAuthorizationDenied | ProfileAuthorizationDependencyUnavailable exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ProfileAuthorizationDependencyUnavailable(exception);
        }
    }

    private static RestClient buildRestClient(
            RestClient.Builder restClientBuilder, ProfileIdentityProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getReadTimeout());
        return restClientBuilder.baseUrl(properties.getBaseUrl()).requestFactory(requestFactory).build();
    }

    private <T> T withOutboundPermit(OutboundCall<T> call) {
        if (!outboundPermits.tryAcquire()) {
            throw new ProfileAuthorizationDependencyUnavailable();
        }
        try {
            return call.execute();
        } finally {
            outboundPermits.release();
        }
    }

    private static void addCorrelationHeader(RestClient.RequestHeadersSpec<?> requestSpec) {
        if (RequestContext.CORRELATION_ID.isBound()) {
            requestSpec.header("X-Correlation-ID", RequestContext.CORRELATION_ID.get());
        }
    }

    private static String extractBearerToken(String authorizationHeader) {
        if (authorizationHeader == null
                || !authorizationHeader.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            throw new ProfileAuthenticationFailure();
        }
        String token = authorizationHeader.substring("Bearer ".length()).trim();
        if (!StringUtils.hasText(token) || token.chars().anyMatch(Character::isWhitespace)) {
            throw new ProfileAuthenticationFailure();
        }
        return token;
    }

    private boolean isUsableDecision(AuthorizationDecisionResponse response) {
        if (response == null
                || !("ALLOW".equals(response.outcome()) || "DENY".equals(response.outcome()))
                || !StringUtils.hasText(response.reasonCode())) {
            return false;
        }
        if ("DENY".equals(response.outcome())) {
            return DENY_REASON_CODES.contains(response.reasonCode());
        }
        return "ALLOWED".equals(response.reasonCode())
                && properties.getExpectedPolicyVersion().equals(response.policyVersion())
                && response.expiresAt() != null
                && response.expiresAt().isAfter(Instant.now());
    }

    private static ProfileSubject toSubject(ValidatedSubjectResponse response) {
        if (response == null
                || response.accountId() == null
                || response.sessionId() == null
                || !StringUtils.hasText(response.authenticationMethod())
                || !isFixedFormatAccessTokenProof(response.accessTokenProof())) {
            throw new ProfileAuthorizationDependencyUnavailable();
        }
        try {
            return new ProfileSubject(
                    response.accountId(), response.sessionId(), response.authenticationMethod(), response.accessTokenProof());
        } catch (IllegalArgumentException exception) {
            throw new ProfileAuthorizationDependencyUnavailable(exception);
        }
    }

    private static boolean isFixedFormatAccessTokenProof(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    @FunctionalInterface
    private interface OutboundCall<T> {
        T execute();
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
