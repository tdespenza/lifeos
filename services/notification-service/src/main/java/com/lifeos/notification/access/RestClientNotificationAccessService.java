package com.lifeos.notification.access;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.lifeos.notification.config.NotificationIdentityProperties;
import com.lifeos.notification.observability.RequestContext;
import com.lifeos.notification.audit.NotificationSecurityAuditOutcome;
import com.lifeos.notification.audit.NotificationSecurityAuditService;
import java.net.http.HttpClient;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Fail-closed REST adapter for identity's workload-authenticated validation endpoint. */
@Component
public class RestClientNotificationAccessService implements NotificationAccessService {

    private static final String VALIDATE_PATH = "/api/v1/auth/validate";
    private static final String WORKLOAD_IDENTITY_HEADER = "X-LifeOS-Workload-Identity";
    private static final String WORKLOAD_TOKEN_HEADER = "X-LifeOS-Workload-Token";

    private final RestClient restClient;
    private final NotificationIdentityProperties properties;
    private final NotificationSecurityAuditService auditService;

    @Autowired
    public RestClientNotificationAccessService(
            RestClient.Builder restClientBuilder,
            NotificationIdentityProperties properties,
            NotificationSecurityAuditService auditService) {
        this(buildClient(restClientBuilder, properties), properties, auditService);
    }

    RestClientNotificationAccessService(
            RestClient restClient, NotificationIdentityProperties properties, NotificationSecurityAuditService auditService) {
        this.restClient = restClient;
        this.properties = properties;
        this.auditService = auditService;
    }

    @Override
    public NotificationSubject authenticate(String authorizationHeader) {
        String token = extractBearerToken(authorizationHeader);
        try {
            RestClient.RequestHeadersSpec<?> request = restClient.get()
                    .uri(VALIDATE_PATH)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .header(WORKLOAD_IDENTITY_HEADER, properties.getWorkloadIdentity())
                    .header(WORKLOAD_TOKEN_HEADER, properties.getWorkloadToken());
            if (RequestContext.CORRELATION_ID.isBound()) {
                request.header("X-Correlation-ID", RequestContext.CORRELATION_ID.get());
            }
            ValidatedSubject response = request.retrieve().body(ValidatedSubject.class);
            NotificationSubject subject = toSubject(response);
            auditService.record(
                    subject.accountId(),
                    subject.sessionId(),
                    "AUTHENTICATION_VALIDATION",
                    NotificationSecurityAuditOutcome.SUCCESS,
                    null,
                    "VALIDATED");
            return subject;
        } catch (NotificationAuthenticationFailure | NotificationAuthenticationDependencyUnavailable exception) {
            auditService.record(
                    null,
                    null,
                    "AUTHENTICATION_VALIDATION",
                    exception instanceof NotificationAuthenticationFailure
                            ? NotificationSecurityAuditOutcome.DENIED
                            : NotificationSecurityAuditOutcome.UNAVAILABLE,
                    null,
                    exception instanceof NotificationAuthenticationFailure ? "REJECTED" : "IDENTITY_UNAVAILABLE");
            throw exception;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 401) {
                auditService.record(
                        null,
                        null,
                        "AUTHENTICATION_VALIDATION",
                        NotificationSecurityAuditOutcome.DENIED,
                        null,
                        "REJECTED");
                throw new NotificationAuthenticationFailure(exception);
            }
            auditService.record(
                    null,
                    null,
                    "AUTHENTICATION_VALIDATION",
                    NotificationSecurityAuditOutcome.UNAVAILABLE,
                    null,
                    "IDENTITY_UNAVAILABLE");
            throw new NotificationAuthenticationDependencyUnavailable(exception);
        } catch (RuntimeException exception) {
            auditService.record(
                    null,
                    null,
                    "AUTHENTICATION_VALIDATION",
                    NotificationSecurityAuditOutcome.UNAVAILABLE,
                    null,
                    "IDENTITY_UNAVAILABLE");
            throw new NotificationAuthenticationDependencyUnavailable(exception);
        }
    }

    private static RestClient buildClient(RestClient.Builder builder, NotificationIdentityProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.getConnectTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getReadTimeout());
        return builder.baseUrl(properties.getBaseUrl().toString()).requestFactory(requestFactory).build();
    }

    private static String extractBearerToken(String header) {
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            throw new NotificationAuthenticationFailure();
        }
        String token = header.substring("Bearer ".length()).trim();
        if (!StringUtils.hasText(token) || token.chars().anyMatch(Character::isWhitespace)) {
            throw new NotificationAuthenticationFailure();
        }
        return token;
    }

    private static NotificationSubject toSubject(ValidatedSubject response) {
        if (response == null || response.accountId() == null || response.sessionId() == null
                || !StringUtils.hasText(response.authenticationMethod())) {
            throw new NotificationAuthenticationDependencyUnavailable();
        }
        try {
            return new NotificationSubject(response.accountId(), response.sessionId(), response.authenticationMethod());
        } catch (IllegalArgumentException exception) {
            throw new NotificationAuthenticationDependencyUnavailable(exception);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ValidatedSubject(UUID accountId, UUID sessionId, String authenticationMethod) {

        @Override
        public String toString() {
            return "ValidatedSubject[redacted]";
        }
    }
}
