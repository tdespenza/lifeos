package com.lifeos.assistant.authorization;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.lifeos.assistant.config.AssistantIdentityProperties;
import com.lifeos.assistant.observability.RequestContext;
import java.net.http.HttpClient;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Bounded, fail-closed REST adapter for Identity session validation.
 *
 * <p>Local ownership is the authorization boundary for this foundation. V2 action descriptors
 * are intentionally deferred until Identity assigns the assistant workload/action family.
 */
@Component
public class RestClientAssistantAccessService implements AssistantAccessService {

    private static final String VALIDATE_PATH = "/api/v1/auth/validate";
    private static final String WORKLOAD_IDENTITY_HEADER = "X-LifeOS-Workload-Identity";
    private static final String WORKLOAD_TOKEN_HEADER = "X-LifeOS-Workload-Token";

    private final RestClient restClient;
    private final AssistantIdentityProperties properties;
    private final Semaphore outboundPermits;

    @Autowired
    public RestClientAssistantAccessService(
            RestClient.Builder restClientBuilder, AssistantIdentityProperties properties) {
        this(buildRestClient(restClientBuilder, properties), properties);
    }

    RestClientAssistantAccessService(RestClient restClient, AssistantIdentityProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
        outboundPermits = new Semaphore(properties.getMaxConcurrentRequests(), true);
    }

    @Override
    public AssistantSubject authenticate(String authorizationHeader) {
        String bearerToken = extractBearerToken(authorizationHeader);
        try {
            return withOutboundPermit(() -> {
                RestClient.RequestHeadersSpec<?> request = restClient.get()
                        .uri(VALIDATE_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                        .header(WORKLOAD_IDENTITY_HEADER, properties.getWorkloadIdentity())
                        .header(WORKLOAD_TOKEN_HEADER, properties.getWorkloadToken());
                addCorrelationHeader(request);
                return toSubject(request.retrieve().body(ValidatedSubjectResponse.class));
            });
        } catch (AssistantAuthenticationFailure | AssistantIdentityDependencyUnavailable exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 401) {
                throw new AssistantAuthenticationFailure(exception);
            }
            throw new AssistantIdentityDependencyUnavailable(exception);
        } catch (RuntimeException exception) {
            throw new AssistantIdentityDependencyUnavailable(exception);
        }
    }

    private static RestClient buildRestClient(RestClient.Builder builder, AssistantIdentityProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.getConnectTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getReadTimeout());
        return builder.baseUrl(properties.getBaseUrl()).requestFactory(requestFactory).build();
    }

    private <T> T withOutboundPermit(OutboundCall<T> call) {
        if (!outboundPermits.tryAcquire()) {
            throw new AssistantIdentityDependencyUnavailable();
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

    private static String extractBearerToken(String header) {
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            throw new AssistantAuthenticationFailure();
        }
        String token = header.substring("Bearer ".length()).trim();
        if (!StringUtils.hasText(token) || token.chars().anyMatch(Character::isWhitespace)) {
            throw new AssistantAuthenticationFailure();
        }
        return token;
    }

    private static AssistantSubject toSubject(ValidatedSubjectResponse response) {
        if (response == null
                || response.accountId() == null
                || response.sessionId() == null
                || !StringUtils.hasText(response.authenticationMethod())
                || response.accessTokenProof() == null
                || !response.accessTokenProof().matches("[0-9a-f]{64}")) {
            throw new AssistantIdentityDependencyUnavailable();
        }
        try {
            return new AssistantSubject(
                    response.accountId(), response.sessionId(), response.authenticationMethod(), response.accessTokenProof());
        } catch (IllegalArgumentException exception) {
            throw new AssistantIdentityDependencyUnavailable(exception);
        }
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
}
