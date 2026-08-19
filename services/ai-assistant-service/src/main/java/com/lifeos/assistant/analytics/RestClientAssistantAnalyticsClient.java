package com.lifeos.assistant.analytics;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.lifeos.assistant.authorization.AssistantSubject;
import com.lifeos.assistant.config.AssistantAnalyticsToolProperties;
import com.lifeos.assistant.observability.RequestContext;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

final class RestClientAssistantAnalyticsClient implements AssistantAnalyticsClient {
    private static final String PATH = "/api/v1/analytics/internal/assistant-insights";
    private final RestClient restClient;
    private final AssistantAnalyticsToolProperties properties;
    private final Semaphore permits;

    RestClientAssistantAnalyticsClient(RestClient restClient, AssistantAnalyticsToolProperties properties, Semaphore permits) {
        this.restClient = restClient;
        this.properties = properties;
        this.permits = permits;
    }

    @Override
    public AnalyticsSnapshot insights(AssistantSubject subject, int periodDays) {
        if (!properties.configured() || !permits.tryAcquire()) throw new AssistantAnalyticsUnavailableException();
        try {
            RestClient.RequestBodySpec request = restClient.post().uri(PATH).contentType(MediaType.APPLICATION_JSON)
                    .header("X-LifeOS-Workload-Identity", properties.getWorkloadIdentity())
                    .header("X-LifeOS-Workload-Token", properties.getWorkloadToken())
                    .header("X-LifeOS-Gateway-Proof", gatewayProof(subject));
            if (RequestContext.CORRELATION_ID.isBound()) request.header("X-Correlation-ID", RequestContext.CORRELATION_ID.get());
            AnalyticsResponse response = request.body(new AnalyticsRequest(
                    subject.accountId(), subject.sessionId(), subject.authenticationMethod(), subject.accessTokenProof(), periodDays))
                    .retrieve().body(AnalyticsResponse.class);
            if (response == null || response.insights() == null || response.limitations() == null) throw new AssistantAnalyticsUnavailableException();
            return new AssistantAnalyticsClient.AnalyticsSnapshot(
                    response.insights().stream().map(i -> new AssistantAnalyticsClient.Insight(i.key(), i.score(), i.evidenceKeys(), i.sourceVersion())).toList(),
                    response.truncated(), response.limitations());
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 401 || exception.getStatusCode().value() == 403) throw new AssistantAnalyticsDeniedException();
            throw new AssistantAnalyticsUnavailableException(exception);
        } catch (RuntimeException exception) {
            throw new AssistantAnalyticsUnavailableException(exception);
        } finally { permits.release(); }
    }

    private String gatewayProof(AssistantSubject subject) {
        try {
            String payload = "POST\n" + PATH + "\n" + subject.accountId() + "\n" + subject.sessionId();
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getGatewayProofSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw new AssistantAnalyticsUnavailableException(exception); }
    }

    private record AnalyticsRequest(UUID subjectId, UUID sessionId, String authenticationMethod, String accessTokenProof, int periodDays) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AnalyticsResponse(List<InsightResponse> insights, boolean truncated, List<String> limitations) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record InsightResponse(String key, int score, List<String> evidenceKeys, String sourceVersion) {}
}
