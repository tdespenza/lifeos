package com.lifeos.taskgoal.projection;

import com.lifeos.taskgoal.authorization.TaskSubject;
import com.lifeos.taskgoal.config.TaskGoalMediaToolProperties;
import com.lifeos.taskgoal.task.TaskService;
import com.lifeos.taskgoal.task.dto.CreateTaskRequest;
import com.lifeos.taskgoal.task.dto.TaskResponse;
import com.lifeos.taskgoal.task.idempotency.InvalidTaskIdempotencyKeyException;
import com.lifeos.taskgoal.task.idempotency.TaskIdempotencyKey;
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
 * Workload-authenticated, owner-proofed task creation for explicitly confirmed Media action items.
 * The workload credential authenticates Media; the subject proof is still re-authorized by the
 * normal TaskService boundary before a task is persisted.
 */
@RestController
public class MediaTaskMutationController {

    static final String PATH = "/api/v1/internal/media/follow-up-tasks";
    private static final String WORKLOAD_IDENTITY = "X-LifeOS-Workload-Identity";
    private static final String WORKLOAD_TOKEN = "X-LifeOS-Workload-Token";

    private final TaskService taskService;
    private final TaskGoalMediaToolProperties properties;

    public MediaTaskMutationController(TaskService taskService, TaskGoalMediaToolProperties properties) {
        this.taskService = taskService;
        this.properties = properties;
    }

    @PostMapping(PATH)
    public ResponseEntity<TaskResponse> create(
            @RequestHeader(value = WORKLOAD_IDENTITY, required = false) String workloadIdentity,
            @RequestHeader(value = WORKLOAD_TOKEN, required = false) String workloadToken,
            @RequestHeader(value = TaskIdempotencyKey.HEADER_NAME, required = false) List<String> idempotencyKeys,
            @Valid @RequestBody FollowUpTaskRequest request) {
        requireWorkload(workloadIdentity, workloadToken);
        TaskSubject subject = new TaskSubject(
                request.subjectId(), request.sessionId(), request.authenticationMethod(), request.accessTokenProof());
        String idempotencyKey = TaskIdempotencyKey.requireSingleHeader(idempotencyKeys);
        TaskResponse response = TaskResponse.from(taskService.create(
                subject, request.title(), request.priority(), request.dueAt(), idempotencyKey));
        return ResponseEntity.created(URI.create("/api/v1/tasks/" + response.id()))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .eTag("\"" + response.version() + "\"")
                .body(response);
    }

    @ExceptionHandler(MediaTaskWorkloadUnauthorizedException.class)
    public ResponseEntity<Void> workloadUnauthorized(MediaTaskWorkloadUnauthorizedException exception) {
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
            throw new MediaTaskWorkloadUnauthorizedException();
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

    /** The caller supplies subject proof and bounded task facts; ownership is never caller-selected. */
    public record FollowUpTaskRequest(
            @NotNull UUID subjectId,
            @NotNull UUID sessionId,
            @NotBlank @Size(max = 32) String authenticationMethod,
            @NotBlank @Size(min = 64, max = 64) String accessTokenProof,
            @NotBlank @Size(max = 255) String title,
            @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(4) Integer priority,
            Instant dueAt) {
    }

    public static class MediaTaskWorkloadUnauthorizedException extends RuntimeException {
    }
}
