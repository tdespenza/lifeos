package com.lifeos.taskgoal.projection;

import com.lifeos.taskgoal.authorization.TaskSubject;
import com.lifeos.taskgoal.config.TaskGoalAssistantToolProperties;
import com.lifeos.taskgoal.task.TaskLifecycleResult;
import com.lifeos.taskgoal.task.TaskService;
import com.lifeos.taskgoal.task.dto.TaskResponse;
import com.lifeos.taskgoal.task.idempotency.TaskIdempotencyKey;
import com.lifeos.taskgoal.task.idempotency.InvalidTaskIdempotencyKeyException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Workload-authenticated task mutation used by the confirmed AI task tool.
 *
 * <p>The caller supplies only an Identity-issued subject proof. TaskService reloads the task
 * authorization facts and asks Identity for the exact {@code task:create} decision before any
 * durable write. The workload credential authenticates the AI service; it never grants user
 * ownership and is deliberately separate from the TaskGoal service's outbound credential.
 */
@RestController
public class AssistantTaskMutationController {

    static final String PATH = "/api/v1/internal/assistant/tasks";
    static final String WORKLOAD_IDENTITY = "X-LifeOS-Workload-Identity";
    static final String WORKLOAD_TOKEN = "X-LifeOS-Workload-Token";

    private final TaskService taskService;
    private final TaskGoalAssistantToolProperties properties;

    public AssistantTaskMutationController(
            TaskService taskService, TaskGoalAssistantToolProperties properties) {
        this.taskService = taskService;
        this.properties = properties;
    }

    @PostMapping(PATH)
    public ResponseEntity<TaskResponse> create(
            @RequestHeader(value = WORKLOAD_IDENTITY, required = false) String workloadIdentity,
            @RequestHeader(value = WORKLOAD_TOKEN, required = false) String workloadToken,
            @RequestHeader(value = TaskIdempotencyKey.HEADER_NAME, required = false)
                    List<String> idempotencyKeys,
            @Valid @RequestBody AssistantTaskMutationRequest request) {
        requireWorkload(workloadIdentity, workloadToken);
        String idempotencyKey = TaskIdempotencyKey.requireSingleHeader(idempotencyKeys);
        TaskSubject subject = new TaskSubject(
                request.subjectId(), request.sessionId(), request.authenticationMethod(), request.accessTokenProof());
        TaskLifecycleResult result = taskService.create(
                subject, request.title(), request.priority(), request.dueAt(), idempotencyKey);
        TaskResponse response = TaskResponse.from(result);
        return ResponseEntity.created(URI.create("/api/v1/tasks/" + response.id()))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .eTag("\"" + response.version() + "\"")
                .body(response);
    }

    @ExceptionHandler(AssistantTaskWorkloadUnauthorizedException.class)
    public ResponseEntity<Void> workloadUnauthorized(AssistantTaskWorkloadUnauthorizedException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    @ExceptionHandler(InvalidTaskIdempotencyKeyException.class)
    public ResponseEntity<Map<String, String>> invalidIdempotencyKey(InvalidTaskIdempotencyKeyException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", "A valid Idempotency-Key header is required"));
    }

    private void requireWorkload(String workloadIdentity, String workloadToken) {
        if (!properties.configured()
                || !constantTimeEquals(properties.getWorkloadIdentity(), workloadIdentity)
                || !constantTimeEquals(properties.getWorkloadToken(), workloadToken)) {
            throw new AssistantTaskWorkloadUnauthorizedException();
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                actual.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Strict internal task command; raw bearer credentials never cross this boundary. */
    public record AssistantTaskMutationRequest(
            @NotNull UUID subjectId,
            @NotNull UUID sessionId,
            @NotBlank @Size(max = 32) String authenticationMethod,
            @NotBlank @Size(min = 64, max = 64) String accessTokenProof,
            @NotBlank @Size(max = 255) String title,
            @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(4) Integer priority,
            Instant dueAt) {
    }

    /** Workload authentication is intentionally generic and non-enumerating. */
    public static class AssistantTaskWorkloadUnauthorizedException extends RuntimeException {
    }
}
