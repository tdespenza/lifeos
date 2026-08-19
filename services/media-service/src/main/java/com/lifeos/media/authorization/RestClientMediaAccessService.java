package com.lifeos.media.authorization;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.lifeos.media.observability.RequestContext;
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

/** Fail-closed, bounded Identity adapter using Media's exact registered V2 actions. */
@Component
public class RestClientMediaAccessService implements MediaAccessService {

    private static final String VALIDATE_PATH = "/api/v1/auth/validate";
    private static final String DECISION_PATH = "/api/v1/internal/authorization/decisions";
    private static final String WORKLOAD_IDENTITY_HEADER = "X-LifeOS-Workload-Identity";
    private static final String WORKLOAD_TOKEN_HEADER = "X-LifeOS-Workload-Token";
    private static final Set<String> DENY_REASONS = Set.of(
            "MALFORMED_REQUEST",
            "STALE_SUBJECT",
            "POLICY_VERSION_MISMATCH",
            "UNSUPPORTED_ACTION",
            "MISSING_ROLE",
            "TENANT_MISMATCH",
            "OWNER_MISMATCH",
            "POLICY_UNAVAILABLE");

    private final RestClient restClient;
    private final MediaIdentityProperties properties;
    private final Semaphore permits;

    @Autowired
    public RestClientMediaAccessService(RestClient.Builder builder, MediaIdentityProperties properties) {
        this(buildRestClient(builder, properties), properties);
    }

    RestClientMediaAccessService(RestClient restClient, MediaIdentityProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
        permits = new Semaphore(properties.getMaxConcurrentRequests(), true);
    }

    @Override
    public MediaSubject authenticate(String authorizationHeader) {
        String token = extractBearer(authorizationHeader);
        try {
            return withPermit(() -> {
                RestClient.RequestHeadersSpec<?> request = restClient.get()
                        .uri(VALIDATE_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(WORKLOAD_IDENTITY_HEADER, properties.getWorkloadIdentity())
                        .header(WORKLOAD_TOKEN_HEADER, properties.getWorkloadToken());
                addCorrelation(request);
                return toSubject(request.retrieve().body(ValidatedSubjectResponse.class));
            });
        } catch (MediaAuthenticationFailure | MediaAuthorizationDependencyUnavailable exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 401) {
                throw new MediaAuthenticationFailure(exception);
            }
            throw new MediaAuthorizationDependencyUnavailable(exception);
        } catch (RuntimeException exception) {
            throw new MediaAuthorizationDependencyUnavailable(exception);
        }
    }

    @Override
    public void authorize(MediaSubject subject, String action, MediaAuthorizationResource resource) {
        try {
            withPermit(() -> {
                RestClient.RequestBodySpec request = restClient.post()
                        .uri(DECISION_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(WORKLOAD_IDENTITY_HEADER, properties.getWorkloadIdentity())
                        .header(WORKLOAD_TOKEN_HEADER, properties.getWorkloadToken())
                        .body(new DecisionRequest(
                                subject.accountId(),
                                subject.sessionId(),
                                subject.accessTokenProof(),
                                action,
                                new DecisionResource(
                                        resource.resourceType(), resource.resourceId(), resource.tenantId(), resource.attributes()),
                                properties.getExpectedPolicyVersion()));
                addCorrelation(request);
                DecisionResponse decision = request.retrieve().body(DecisionResponse.class);
                if (!isUsableDecision(decision) || "POLICY_UNAVAILABLE".equals(decision.reasonCode())) {
                    throw new MediaAuthorizationDependencyUnavailable();
                }
                if (!"ALLOW".equals(decision.outcome())) {
                    throw new MediaAuthorizationDenied();
                }
                return null;
            });
        } catch (MediaAuthorizationDenied | MediaAuthorizationDependencyUnavailable exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaAuthorizationDependencyUnavailable(exception);
        }
    }

    private static RestClient buildRestClient(RestClient.Builder builder, MediaIdentityProperties properties) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(properties.getConnectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(properties.getReadTimeout());
        return builder.baseUrl(properties.getBaseUrl()).requestFactory(factory).build();
    }

    private <T> T withPermit(OutboundCall<T> call) {
        if (!permits.tryAcquire()) {
            throw new MediaAuthorizationDependencyUnavailable();
        }
        try {
            return call.execute();
        } finally {
            permits.release();
        }
    }

    private static void addCorrelation(RestClient.RequestHeadersSpec<?> request) {
        if (RequestContext.CORRELATION_ID.isBound()) {
            request.header("X-Correlation-ID", RequestContext.CORRELATION_ID.get());
        }
    }

    private static String extractBearer(String header) {
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            throw new MediaAuthenticationFailure();
        }
        String token = header.substring("Bearer ".length()).trim();
        if (!StringUtils.hasText(token) || token.chars().anyMatch(Character::isWhitespace)) {
            throw new MediaAuthenticationFailure();
        }
        return token;
    }

    private boolean isUsableDecision(DecisionResponse value) {
        if (value == null
                || !("ALLOW".equals(value.outcome()) || "DENY".equals(value.outcome()))
                || !StringUtils.hasText(value.reasonCode())) {
            return false;
        }
        if ("DENY".equals(value.outcome())) {
            return DENY_REASONS.contains(value.reasonCode());
        }
        return "ALLOWED".equals(value.reasonCode())
                && properties.getExpectedPolicyVersion().equals(value.policyVersion())
                && value.expiresAt() != null
                && value.expiresAt().isAfter(Instant.now());
    }

    private static MediaSubject toSubject(ValidatedSubjectResponse value) {
        if (value == null
                || value.accountId() == null
                || value.sessionId() == null
                || !StringUtils.hasText(value.authenticationMethod())
                || value.accessTokenProof() == null
                || !value.accessTokenProof().matches("[0-9a-f]{64}")) {
            throw new MediaAuthorizationDependencyUnavailable();
        }
        return new MediaSubject(value.accountId(), value.sessionId(), value.authenticationMethod(), value.accessTokenProof());
    }

    @FunctionalInterface
    private interface OutboundCall<T> {
        T execute();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ValidatedSubjectResponse(UUID accountId, UUID sessionId, String authenticationMethod, String accessTokenProof) {

        @Override
        public String toString() {
            return "ValidatedSubjectResponse[redacted]";
        }
    }

    record DecisionRequest(
            UUID subjectId,
            UUID sessionId,
            String accessTokenProof,
            String action,
            DecisionResource resource,
            String expectedPolicyVersion) {

        @Override
        public String toString() {
            return "DecisionRequest[redacted]";
        }
    }

    record DecisionResource(String resourceType, String resourceId, String tenantId, Map<String, String> attributes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DecisionResponse(String outcome, String reasonCode, String policyVersion, Instant expiresAt) {
    }
}
