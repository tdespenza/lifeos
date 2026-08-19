package com.lifeos.trustledger.certificate;

import com.lifeos.trustledger.access.TrustSubject;
import com.lifeos.trustledger.observability.RequestContext;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.net.http.HttpClient;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Loads completion facts from Task/Goal through its workload-authenticated narrow projection. */
@Component
public class RestClientTaskGoalCertificateService implements TaskGoalCertificateService {

    private final RestClient restClient;
    private final TaskGoalCertificateProperties properties;
    private final Semaphore permits;

    @Autowired
    public RestClientTaskGoalCertificateService(
            RestClient.Builder builder, TaskGoalCertificateProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.getConnectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.getReadTimeout());
        restClient = builder.baseUrl(properties.getBaseUrl()).requestFactory(factory).build();
        this.properties = properties;
        permits = new Semaphore(properties.getMaxConcurrentRequests(), true);
    }

    @Override
    public GoalCertificateFacts load(TrustSubject subject, UUID goalId) {
        if (!permits.tryAcquire()) {
            throw new TaskGoalCertificateUnavailableException();
        }
        try {
            RestClient.RequestBodySpec request = restClient.post()
                    .uri("/api/v1/internal/goals/{goalId}/certificate-facts", goalId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-LifeOS-Workload-Identity", properties.getWorkloadIdentity())
                    .header("X-LifeOS-Workload-Token", properties.getWorkloadToken())
                    .body(new ProjectionRequest(
                            subject.accountId(), subject.sessionId(), subject.authenticationMethod(), subject.accessTokenProof()));
            if (RequestContext.CORRELATION_ID.isBound()) {
                request.header("X-Correlation-ID", RequestContext.CORRELATION_ID.get());
            }
            ProjectionResponse response = request.retrieve().body(ProjectionResponse.class);
            if (response == null || !goalId.equals(response.goalId()) || response.goalVersion() < 0
                    || response.completedAt() == null) {
                throw new TaskGoalCertificateUnavailableException();
            }
            return new GoalCertificateFacts(response.goalId(), response.goalVersion(), response.completedAt());
        } catch (TaskGoalCertificateUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new TaskGoalCertificateUnavailableException(exception);
        } finally {
            permits.release();
        }
    }

    record ProjectionRequest(UUID subjectId, UUID sessionId, String authenticationMethod, String accessTokenProof) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ProjectionResponse(UUID goalId, long goalVersion, java.time.Instant completedAt) {}
}
