package com.lifeos.taskgoal.projection;

import com.lifeos.taskgoal.authorization.TaskSubject;
import com.lifeos.taskgoal.config.TaskGoalAssistantToolProperties;
import com.lifeos.taskgoal.goal.GoalService;
import com.lifeos.taskgoal.goal.dto.GoalResponse;
import com.lifeos.taskgoal.goal.idempotency.GoalIdempotencyKey;
import com.lifeos.taskgoal.goal.idempotency.InvalidGoalIdempotencyKeyException;
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

/** Workload-authenticated goal mutation used by the confirmed AI goal tool. */
@RestController
public class AssistantGoalMutationController {
    static final String PATH = "/api/v1/internal/assistant/goals";
    private final GoalService goalService;
    private final TaskGoalAssistantToolProperties properties;

    public AssistantGoalMutationController(GoalService goalService, TaskGoalAssistantToolProperties properties) {
        this.goalService = goalService;
        this.properties = properties;
    }

    @PostMapping(PATH)
    public ResponseEntity<GoalResponse> create(
            @RequestHeader(value = "X-LifeOS-Workload-Identity", required = false) String workloadIdentity,
            @RequestHeader(value = "X-LifeOS-Workload-Token", required = false) String workloadToken,
            @RequestHeader(value = GoalIdempotencyKey.HEADER_NAME, required = false) List<String> idempotencyKeys,
            @Valid @RequestBody AssistantGoalMutationRequest request) {
        requireWorkload(workloadIdentity, workloadToken);
        TaskSubject subject = new TaskSubject(
                request.subjectId(), request.sessionId(), request.authenticationMethod(), request.accessTokenProof());
        GoalResponse response = GoalResponse.from(goalService.create(
                subject, request.title(), request.priority(), request.dueAt(), GoalIdempotencyKey.requireSingleHeader(idempotencyKeys)));
        return ResponseEntity.created(URI.create("/api/v1/goals/" + response.id()))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .eTag("\"" + response.version() + "\"")
                .body(response);
    }

    @ExceptionHandler(AssistantGoalWorkloadUnauthorizedException.class)
    public ResponseEntity<Void> workloadUnauthorized(AssistantGoalWorkloadUnauthorizedException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    @ExceptionHandler(InvalidGoalIdempotencyKeyException.class)
    public ResponseEntity<Map<String, String>> invalidIdempotencyKey(InvalidGoalIdempotencyKeyException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", "A valid Idempotency-Key header is required"));
    }

    private void requireWorkload(String workloadIdentity, String workloadToken) {
        if (!properties.configured()
                || !constantTimeEquals(properties.getWorkloadIdentity(), workloadIdentity)
                || !constantTimeEquals(properties.getWorkloadToken(), workloadToken)) {
            throw new AssistantGoalWorkloadUnauthorizedException();
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) return false;
        return java.security.MessageDigest.isEqual(
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8), actual.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public record AssistantGoalMutationRequest(
            @NotNull UUID subjectId,
            @NotNull UUID sessionId,
            @NotBlank @Size(max = 32) String authenticationMethod,
            @NotBlank @Size(min = 64, max = 64) String accessTokenProof,
            @NotBlank @Size(max = 255) String title,
            @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(4) Integer priority,
            Instant dueAt) {}

    public static class AssistantGoalWorkloadUnauthorizedException extends RuntimeException {}
}
