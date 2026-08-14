package com.lifeos.gateway.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.lifeos.gateway.config.GatewayAuthenticationProperties;
import com.lifeos.gateway.observability.RequestContext;
import java.net.http.HttpClient;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Fail-closed adapter to identity-service's internal JWT/session validation boundary.
 *
 * <p>JWT cryptographic and claim validation remains owned by identity-service, which also checks
 * the durable session/revocation authority. The gateway retains only sanitized subject facts and
 * never logs or forwards the raw bearer value to a security metric.
 */
@Component
public class GatewayAuthenticationClient {

    static final String VALIDATE_PATH = "/api/v1/auth/validate";
    static final String WORKLOAD_IDENTITY_HEADER = "X-LifeOS-Workload-Identity";
    static final String WORKLOAD_TOKEN_HEADER = "X-LifeOS-Workload-Token";
    private static final Pattern ACCESS_TOKEN_PROOF_PATTERN = Pattern.compile("[0-9a-f]{64}");

    private final RestClient restClient;
    private final GatewayAuthenticationProperties properties;

    /**
     * Creates the bounded identity validation client.
     *
     * @param properties identity authority settings
     */
    @Autowired
    public GatewayAuthenticationClient(GatewayAuthenticationProperties properties) {
        this(buildRestClient(properties), properties);
    }

    /**
     * Creates a client around a supplied HTTP client, primarily for isolated contract tests.
     *
     * @param restClient bounded identity HTTP client
     * @param properties workload identity settings
     */
    public GatewayAuthenticationClient(RestClient restClient, GatewayAuthenticationProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    /**
     * Validates one inbound bearer header and returns sanitized subject facts.
     *
     * @param authorizationHeader inbound authorization header
     * @return validated subject context
     * @throws GatewayAuthenticationFailureException when identity rejects the credential
     * @throws GatewayAuthenticationDependencyUnavailableException when identity cannot decide
     */
    public GatewayAuthenticatedSubject authenticate(String authorizationHeader) {
        String token = extractBearerToken(authorizationHeader);
        try {
            RestClient.RequestHeadersSpec<?> requestSpec = restClient.get()
                    .uri(VALIDATE_PATH)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .header(WORKLOAD_IDENTITY_HEADER, properties.getWorkloadIdentity())
                    .header(WORKLOAD_TOKEN_HEADER, properties.getWorkloadToken());
            if (RequestContext.CORRELATION_ID.isBound()) {
                requestSpec.header("X-Correlation-ID", RequestContext.CORRELATION_ID.get());
            }
            ValidatedSubjectResponse response = requestSpec.retrieve().body(ValidatedSubjectResponse.class);
            return toSubject(response);
        } catch (GatewayAuthenticationFailureException
                | GatewayAuthenticationDependencyUnavailableException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 401 || exception.getStatusCode().value() == 403) {
                throw new GatewayAuthenticationFailureException(exception);
            }
            throw new GatewayAuthenticationDependencyUnavailableException(exception);
        } catch (RestClientException exception) {
            throw new GatewayAuthenticationDependencyUnavailableException(exception);
        }
    }

    private static RestClient buildRestClient(GatewayAuthenticationProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
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
            throw new GatewayAuthenticationFailureException();
        }
        String token = authorizationHeader.substring("Bearer ".length()).trim();
        if (!StringUtils.hasText(token)
                || token.chars().anyMatch(
                        character -> Character.isWhitespace(character) || Character.isISOControl(character))) {
            throw new GatewayAuthenticationFailureException();
        }
        return token;
    }

    private static GatewayAuthenticatedSubject toSubject(ValidatedSubjectResponse response) {
        if (response == null
                || response.accountId() == null
                || response.sessionId() == null
                || !StringUtils.hasText(response.authenticationMethod())
                || !isFixedFormatAccessTokenProof(response.accessTokenProof())) {
            throw new GatewayAuthenticationDependencyUnavailableException();
        }
        try {
            return new GatewayAuthenticatedSubject(
                    response.accountId(), response.sessionId(), response.authenticationMethod());
        } catch (IllegalArgumentException exception) {
            throw new GatewayAuthenticationDependencyUnavailableException(exception);
        }
    }

    private static boolean isFixedFormatAccessTokenProof(String value) {
        return value != null && ACCESS_TOKEN_PROOF_PATTERN.matcher(value).matches();
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
}
