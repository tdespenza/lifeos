package com.lifeos.documentvault.authorization;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.lifeos.documentvault.observability.RequestContext;
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

/** Bounded, fail-closed Identity validation and exact V2 authorization-decision adapter. */
@Component
public class RestClientDocumentVaultAccessService implements DocumentVaultAccessService {

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
    private final DocumentVaultIdentityProperties properties;
    private final Semaphore outboundPermits;

    @Autowired
    public RestClientDocumentVaultAccessService(
            RestClient.Builder restClientBuilder, DocumentVaultIdentityProperties properties) {
        this(buildRestClient(restClientBuilder, properties), properties);
    }

    RestClientDocumentVaultAccessService(RestClient restClient, DocumentVaultIdentityProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
        outboundPermits = new Semaphore(properties.getMaxConcurrentRequests(), true);
    }

    @Override
    public DocumentVaultSubject authenticate(String authorizationHeader) {
        String token = extractBearerToken(authorizationHeader);
        try {
            return withOutboundPermit(() -> {
                RestClient.RequestHeadersSpec<?> request = restClient.get()
                        .uri(VALIDATE_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(WORKLOAD_IDENTITY_HEADER, properties.getWorkloadIdentity())
                        .header(WORKLOAD_TOKEN_HEADER, properties.getWorkloadToken());
                addCorrelationHeader(request);
                return toSubject(request.retrieve().body(ValidatedSubjectResponse.class));
            });
        } catch (DocumentVaultAuthenticationFailure | DocumentVaultAuthorizationDependencyUnavailable exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 401) {
                throw new DocumentVaultAuthenticationFailure(exception);
            }
            throw new DocumentVaultAuthorizationDependencyUnavailable(exception);
        } catch (RuntimeException exception) {
            throw new DocumentVaultAuthorizationDependencyUnavailable(exception);
        }
    }

    @Override
    public void authorize(DocumentVaultSubject subject, String action, DocumentVaultAuthorizationResource resource) {
        try {
            withOutboundPermit(() -> {
                RestClient.RequestBodySpec request = restClient.post()
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
                addCorrelationHeader(request);
                AuthorizationDecisionResponse response = request.retrieve()
                        .body(AuthorizationDecisionResponse.class);
                if (!isUsableDecision(response) || "POLICY_UNAVAILABLE".equals(response.reasonCode())) {
                    throw new DocumentVaultAuthorizationDependencyUnavailable();
                }
                if (!"ALLOW".equals(response.outcome())) {
                    throw new DocumentVaultAuthorizationDenied();
                }
                return null;
            });
        } catch (DocumentVaultAuthorizationDenied | DocumentVaultAuthorizationDependencyUnavailable exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new DocumentVaultAuthorizationDependencyUnavailable(exception);
        }
    }

    private static RestClient buildRestClient(
            RestClient.Builder restClientBuilder, DocumentVaultIdentityProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.getConnectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.getReadTimeout());
        return restClientBuilder.baseUrl(properties.getBaseUrl()).requestFactory(factory).build();
    }

    private <T> T withOutboundPermit(OutboundCall<T> call) {
        if (!outboundPermits.tryAcquire()) {
            throw new DocumentVaultAuthorizationDependencyUnavailable();
        }
        try {
            return call.execute();
        } finally {
            outboundPermits.release();
        }
    }

    private static void addCorrelationHeader(RestClient.RequestHeadersSpec<?> request) {
        if (RequestContext.CORRELATION_ID.isBound()) {
            request.header("X-Correlation-ID", RequestContext.CORRELATION_ID.get());
        }
    }

    private static String extractBearerToken(String authorizationHeader) {
        if (authorizationHeader == null
                || !authorizationHeader.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            throw new DocumentVaultAuthenticationFailure();
        }
        String token = authorizationHeader.substring("Bearer ".length()).trim();
        if (!StringUtils.hasText(token) || token.chars().anyMatch(Character::isWhitespace)) {
            throw new DocumentVaultAuthenticationFailure();
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

    private static DocumentVaultSubject toSubject(ValidatedSubjectResponse response) {
        if (response == null
                || response.accountId() == null
                || response.sessionId() == null
                || !StringUtils.hasText(response.authenticationMethod())
                || response.accessTokenProof() == null
                || !response.accessTokenProof().matches("[0-9a-f]{64}")) {
            throw new DocumentVaultAuthorizationDependencyUnavailable();
        }
        return new DocumentVaultSubject(
                response.accountId(), response.sessionId(), response.authenticationMethod(), response.accessTokenProof());
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

    record AuthorizationDecisionRequest(
            UUID subjectId,
            UUID sessionId,
            String accessTokenProof,
            String action,
            DecisionResource resource,
            String expectedPolicyVersion) {

        @Override
        public String toString() {
            return "AuthorizationDecisionRequest[redacted]";
        }
    }

    record DecisionResource(String resourceType, String resourceId, String tenantId, Map<String, String> attributes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AuthorizationDecisionResponse(String outcome, String reasonCode, String policyVersion, Instant expiresAt) {
    }
}
