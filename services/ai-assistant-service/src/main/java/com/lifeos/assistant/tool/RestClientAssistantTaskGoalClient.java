package com.lifeos.assistant.tool;

import com.lifeos.assistant.authorization.AssistantSubject;
import com.lifeos.assistant.config.AssistantTaskGoalToolProperties;
import com.lifeos.assistant.observability.RequestContext;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Workload-authenticated, non-retrying TaskGoal adapter for confirmed task creation. */
final class RestClientAssistantTaskGoalClient implements AssistantTaskGoalClient {

    private static final String PATH = "/api/v1/internal/assistant/tasks";
    private static final String PLANNING_PATH = "/api/v1/internal/assistant/planning-snapshot";
    private static final String GOAL_PATH = "/api/v1/internal/assistant/goals";
    private static final String WORKLOAD_IDENTITY = "X-LifeOS-Workload-Identity";
    private static final String WORKLOAD_TOKEN = "X-LifeOS-Workload-Token";

    private final RestClient restClient;
    private final AssistantTaskGoalToolProperties properties;
    private final Semaphore permits;

    RestClientAssistantTaskGoalClient(
            RestClient restClient, AssistantTaskGoalToolProperties properties, Semaphore permits) {
        this.restClient = restClient;
        this.properties = properties;
        this.permits = permits;
    }

    @Override
    public TaskCreationResult createTask(
            AssistantSubject subject, String title, Integer priority, Instant dueAt, String idempotencyKey) {
        if (!properties.configured() || !permits.tryAcquire()) {
            throw new AssistantTaskToolUnavailableException();
        }
        try {
            RestClient.RequestBodySpec request = restClient.post()
                    .uri(PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(WORKLOAD_IDENTITY, properties.getWorkloadIdentity())
                    .header(WORKLOAD_TOKEN, properties.getWorkloadToken())
                    .header("Idempotency-Key", idempotencyKey)
                    .body(new AssistantTaskMutationRequest(
                            subject.accountId(),
                            subject.sessionId(),
                            subject.authenticationMethod(),
                            subject.accessTokenProof(),
                            title,
                            priority,
                            dueAt));
            if (RequestContext.CORRELATION_ID.isBound()) {
                request.header("X-Correlation-ID", RequestContext.CORRELATION_ID.get());
            }
            return request.retrieve().body(TaskCreationResult.class);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 401 || exception.getStatusCode().value() == 403) {
                throw new AssistantTaskToolDeniedException();
            }
            throw new AssistantTaskToolUnavailableException(exception);
        } catch (RuntimeException exception) {
            throw new AssistantTaskToolUnavailableException(exception);
        } finally {
            permits.release();
        }
    }

    @Override
    public TaskCreationResult createGoal(
            AssistantSubject subject, String title, Integer priority, Instant dueAt, String idempotencyKey) {
        if (!properties.configured() || !permits.tryAcquire()) {
            throw new AssistantTaskToolUnavailableException();
        }
        try {
            RestClient.RequestBodySpec request = restClient.post()
                    .uri(GOAL_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(WORKLOAD_IDENTITY, properties.getWorkloadIdentity())
                    .header(WORKLOAD_TOKEN, properties.getWorkloadToken())
                    .header("Idempotency-Key", idempotencyKey)
                    .body(new AssistantGoalMutationRequest(
                            subject.accountId(), subject.sessionId(), subject.authenticationMethod(), subject.accessTokenProof(), title, priority, dueAt));
            if (RequestContext.CORRELATION_ID.isBound()) request.header("X-Correlation-ID", RequestContext.CORRELATION_ID.get());
            GoalCreationResponse response = request.retrieve().body(GoalCreationResponse.class);
            if (response == null) throw new AssistantTaskToolUnavailableException();
            return new TaskCreationResult(
                    response.id(), response.title(), response.status(), response.version(), response.createdAt(), response.updatedAt(),
                    response.completedAt(), null, response.priority(), response.dueAt());
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 401 || exception.getStatusCode().value() == 403) throw new AssistantTaskToolDeniedException();
            throw new AssistantTaskToolUnavailableException(exception);
        } catch (RuntimeException exception) {
            throw new AssistantTaskToolUnavailableException(exception);
        } finally { permits.release(); }
    }

    @Override
    public PlanningSnapshot planningSnapshot(AssistantSubject subject, int maxResults) {
        if (!properties.configured() || !permits.tryAcquire()) {
            throw new AssistantTaskToolUnavailableException();
        }
        try {
            RestClient.RequestBodySpec request = restClient.post()
                    .uri(PLANNING_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(WORKLOAD_IDENTITY, properties.getWorkloadIdentity())
                    .header(WORKLOAD_TOKEN, properties.getWorkloadToken())
                    .body(new PlanningSnapshotRequest(
                            subject.accountId(),
                            subject.sessionId(),
                            subject.authenticationMethod(),
                            subject.accessTokenProof(),
                            maxResults));
            if (RequestContext.CORRELATION_ID.isBound()) {
                request.header("X-Correlation-ID", RequestContext.CORRELATION_ID.get());
            }
            return request.retrieve().body(PlanningSnapshot.class);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 401 || exception.getStatusCode().value() == 403) {
                throw new AssistantTaskToolDeniedException();
            }
            throw new AssistantTaskToolUnavailableException(exception);
        } catch (RuntimeException exception) {
            throw new AssistantTaskToolUnavailableException(exception);
        } finally {
            permits.release();
        }
    }

    private record AssistantTaskMutationRequest(
            UUID subjectId,
            UUID sessionId,
            String authenticationMethod,
            String accessTokenProof,
            String title,
            Integer priority,
            Instant dueAt) {
    }

    private record AssistantGoalMutationRequest(
            UUID subjectId, UUID sessionId, String authenticationMethod, String accessTokenProof,
            String title, Integer priority, Instant dueAt) {}

    private record GoalCreationResponse(
            UUID id, String title, String status, long version, Instant createdAt, Instant updatedAt,
            Instant completedAt, Instant archivedAt, int priority, Instant dueAt) {}

    private record PlanningSnapshotRequest(
            UUID subjectId,
            UUID sessionId,
            String authenticationMethod,
            String accessTokenProof,
            int maxResults) {
    }

}
