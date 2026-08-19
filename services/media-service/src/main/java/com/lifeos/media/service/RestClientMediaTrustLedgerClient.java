package com.lifeos.media.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.lifeos.media.authorization.MediaSubject;
import com.lifeos.media.config.MediaTrustLedgerProperties;
import com.lifeos.media.observability.RequestContext;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Bounded non-retrying adapter; Trust Ledger owns durable anchor state and external retries. */
@Component
public class RestClientMediaTrustLedgerClient implements MediaTrustLedgerClient {

    private static final String PATH = "/api/v1/internal/trust/session-summary-anchors";
    private static final String IDENTITY = "X-LifeOS-Workload-Identity";
    private static final String TOKEN = "X-LifeOS-Workload-Token";
    private final RestClient restClient;
    private final MediaTrustLedgerProperties properties;
    private final Semaphore permits;

    public RestClientMediaTrustLedgerClient(
            RestClient.Builder builder, MediaTrustLedgerProperties properties) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(properties.getReadTimeout());
        restClient = builder.baseUrl(properties.getBaseUrl()).requestFactory(factory).build();
        this.properties = properties;
        permits = new Semaphore(properties.getMaxConcurrentRequests(), true);
    }

    @Override
    public AnchorResult anchorSessionSummary(
            MediaSubject subject,
            UUID artifactId,
            long artifactVersion,
            String digestSha256,
            String idempotencyKey) {
        if (!properties.configured() || !permits.tryAcquire()) {
            throw new MediaTrustLedgerUnavailableException();
        }
        try {
            RestClient.RequestBodySpec request = restClient.post()
                    .uri(PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(IDENTITY, properties.getWorkloadIdentity())
                    .header(TOKEN, properties.getWorkloadToken())
                    .header("Idempotency-Key", idempotencyKey)
                    .body(new SessionSummaryAnchorRequest(
                            subject.accountId(), subject.sessionId(), subject.authenticationMethod(),
                            subject.accessTokenProof(), artifactId, artifactVersion, digestSha256));
            if (RequestContext.CORRELATION_ID.isBound()) {
                request.header("X-Correlation-ID", RequestContext.CORRELATION_ID.get());
            }
            AnchorResultResponse result = request.retrieve().body(AnchorResultResponse.class);
            if (result == null || result.requestId() == null || result.state() == null) {
                throw new MediaTrustLedgerUnavailableException();
            }
            return new AnchorResult(
                    result.requestId(), result.subjectType(), result.subjectId(), result.subjectVersion(),
                    result.digestSha256(), result.state(), result.transactionHash(), result.blockNumber(), result.updatedAt());
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 401 || exception.getStatusCode().value() == 403) {
                throw new MediaTrustLedgerDeniedException(exception);
            }
            throw new MediaTrustLedgerUnavailableException(exception);
        } catch (RuntimeException exception) {
            if (exception instanceof MediaTrustLedgerDeniedException
                    || exception instanceof MediaTrustLedgerUnavailableException) {
                throw exception;
            }
            throw new MediaTrustLedgerUnavailableException(exception);
        } finally {
            permits.release();
        }
    }

    private record SessionSummaryAnchorRequest(
            UUID subjectId,
            UUID sessionId,
            String authenticationMethod,
            String accessTokenProof,
            UUID artifactId,
            long artifactVersion,
            String digestSha256) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AnchorResultResponse(
            UUID requestId,
            String subjectType,
            UUID subjectId,
            long subjectVersion,
            String digestSha256,
            String state,
            String transactionHash,
            Long blockNumber,
            Instant updatedAt) {
    }
}
