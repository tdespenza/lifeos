package com.lifeos.media.service;

import com.lifeos.media.authorization.MediaSubject;
import com.lifeos.media.config.MediaTaskGoalProperties;
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

/** Bounded, non-retrying adapter; durable idempotency is owned by both sides of the command. */
@Component
public class RestClientMediaTaskGoalClient implements MediaTaskGoalClient {

    private static final String PATH = "/api/v1/internal/media/follow-up-tasks";
    private static final String IDENTITY = "X-LifeOS-Workload-Identity";
    private static final String TOKEN = "X-LifeOS-Workload-Token";
    private final RestClient restClient;
    private final MediaTaskGoalProperties properties;
    private final Semaphore permits;

    public RestClientMediaTaskGoalClient(RestClient.Builder builder, MediaTaskGoalProperties properties) {
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
    public TaskCreationResult createTask(
            MediaSubject subject, String title, Integer priority, Instant dueAt, String idempotencyKey) {
        if (!properties.configured() || !permits.tryAcquire()) {
            throw new MediaTaskGoalUnavailableException();
        }
        try {
            RestClient.RequestBodySpec request = restClient.post()
                    .uri(PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(IDENTITY, properties.getWorkloadIdentity())
                    .header(TOKEN, properties.getWorkloadToken())
                    .header("Idempotency-Key", idempotencyKey)
                    .body(new FollowUpTaskRequest(
                            subject.accountId(), subject.sessionId(), subject.authenticationMethod(),
                            subject.accessTokenProof(), title, priority, dueAt));
            if (RequestContext.CORRELATION_ID.isBound()) {
                request.header("X-Correlation-ID", RequestContext.CORRELATION_ID.get());
            }
            TaskCreationResult result = request.retrieve().body(TaskCreationResult.class);
            if (result == null || result.id() == null) throw new MediaTaskGoalUnavailableException();
            return result;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 401 || exception.getStatusCode().value() == 403) {
                throw new MediaTaskGoalDeniedException(exception);
            }
            throw new MediaTaskGoalUnavailableException(exception);
        } catch (RuntimeException exception) {
            if (exception instanceof MediaTaskGoalDeniedException || exception instanceof MediaTaskGoalUnavailableException) {
                throw exception;
            }
            throw new MediaTaskGoalUnavailableException(exception);
        } finally {
            permits.release();
        }
    }

    private record FollowUpTaskRequest(
            UUID subjectId, UUID sessionId, String authenticationMethod, String accessTokenProof,
            String title, Integer priority, Instant dueAt) { }
}
