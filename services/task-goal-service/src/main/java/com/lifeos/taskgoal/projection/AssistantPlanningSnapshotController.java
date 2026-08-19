package com.lifeos.taskgoal.projection;

import com.lifeos.taskgoal.authorization.GoalAuthorizationActions;
import com.lifeos.taskgoal.authorization.GoalAuthorizationResource;
import com.lifeos.taskgoal.authorization.TaskAccessService;
import com.lifeos.taskgoal.authorization.TaskAuthorizationActions;
import com.lifeos.taskgoal.authorization.TaskAuthorizationResource;
import com.lifeos.taskgoal.authorization.TaskSubject;
import com.lifeos.taskgoal.config.TaskGoalAssistantToolProperties;
import com.lifeos.taskgoal.goal.Goal;
import com.lifeos.taskgoal.goal.GoalRepository;
import com.lifeos.taskgoal.goal.GoalStatus;
import com.lifeos.taskgoal.task.Task;
import com.lifeos.taskgoal.task.TaskRepository;
import com.lifeos.taskgoal.task.TaskStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Owner-authorized, bounded planning facts for deterministic assistant recommendations. */
@RestController
public class AssistantPlanningSnapshotController {

    static final String PATH = "/api/v1/internal/assistant/planning-snapshot";
    static final String WORKLOAD_IDENTITY = "X-LifeOS-Workload-Identity";
    static final String WORKLOAD_TOKEN = "X-LifeOS-Workload-Token";

    private static final Comparator<PlanningFact> PRIORITY_ORDER = Comparator
            .comparing(PlanningFact::dueAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparingInt(PlanningFact::priority)
            .thenComparing(PlanningFact::resourceId);

    private final TaskRepository taskRepository;
    private final GoalRepository goalRepository;
    private final TaskAccessService accessService;
    private final TaskGoalAssistantToolProperties properties;

    public AssistantPlanningSnapshotController(
            TaskRepository taskRepository,
            GoalRepository goalRepository,
            TaskAccessService accessService,
            TaskGoalAssistantToolProperties properties) {
        this.taskRepository = taskRepository;
        this.goalRepository = goalRepository;
        this.accessService = accessService;
        this.properties = properties;
    }

    @PostMapping(PATH)
    @Transactional(readOnly = true)
    public ResponseEntity<PlanningSnapshotResponse> snapshot(
            @RequestHeader(value = WORKLOAD_IDENTITY, required = false) String workloadIdentity,
            @RequestHeader(value = WORKLOAD_TOKEN, required = false) String workloadToken,
            @Valid @RequestBody PlanningSnapshotRequest request) {
        requireWorkload(workloadIdentity, workloadToken);
        TaskSubject subject = new TaskSubject(
                request.subjectId(), request.sessionId(), request.authenticationMethod(), request.accessTokenProof());
        accessService.authorize(
                subject, TaskAuthorizationActions.LIST, TaskAuthorizationResource.forCollection(subject.tenantId()));
        accessService.authorize(
                subject, GoalAuthorizationActions.LIST, GoalAuthorizationResource.forCollection(subject.tenantId()));

        List<PlanningFact> facts = new ArrayList<>();
        taskRepository.findAllByOwnerAccountIdAndTenantIdOrderByCreatedAtAscIdAsc(
                        subject.accountId(), subject.tenantId())
                .stream()
                .filter(task -> task.getStatus() == TaskStatus.ACTIVE)
                .map(AssistantPlanningSnapshotController::taskFact)
                .forEach(facts::add);
        goalRepository.findAllByOwnerAccountIdAndTenantIdOrderByCreatedAtAscIdAsc(
                        subject.accountId(), subject.tenantId())
                .stream()
                .filter(goal -> goal.getStatus() == GoalStatus.ACTIVE)
                .map(AssistantPlanningSnapshotController::goalFact)
                .forEach(facts::add);

        return ResponseEntity.ok(new PlanningSnapshotResponse(
                facts.stream().sorted(PRIORITY_ORDER).limit(request.maxResults()).toList()));
    }

    @ExceptionHandler(AssistantPlanningWorkloadUnauthorizedException.class)
    public ResponseEntity<Void> workloadUnauthorized(AssistantPlanningWorkloadUnauthorizedException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    private void requireWorkload(String workloadIdentity, String workloadToken) {
        if (!properties.configured()
                || !constantTimeEquals(properties.getWorkloadIdentity(), workloadIdentity)
                || !constantTimeEquals(properties.getWorkloadToken(), workloadToken)) {
            throw new AssistantPlanningWorkloadUnauthorizedException();
        }
    }

    private static PlanningFact taskFact(Task task) {
        return new PlanningFact(
                "TASK", task.getId(), task.getTitle(), task.getStatus().name(), task.getPriority(), task.getDueAt());
    }

    private static PlanningFact goalFact(Goal goal) {
        return new PlanningFact(
                "GOAL", goal.getId(), goal.getTitle(), goal.getStatus().name(), goal.getPriority(), goal.getDueAt());
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                actual.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public record PlanningSnapshotRequest(
            @NotNull UUID subjectId,
            @NotNull UUID sessionId,
            @NotBlank @Size(max = 32) String authenticationMethod,
            @NotBlank @Size(min = 64, max = 64) String accessTokenProof,
            @NotNull @Min(1) @Max(32) Integer maxResults) {
    }

    public record PlanningSnapshotResponse(List<PlanningFact> facts) {

        public PlanningSnapshotResponse {
            facts = List.copyOf(facts);
        }
    }

    public record PlanningFact(
            String resourceType,
            UUID resourceId,
            String title,
            String status,
            int priority,
            Instant dueAt) {
    }

    /** Inbound workload authentication has no user-facing disclosure semantics. */
    public static class AssistantPlanningWorkloadUnauthorizedException extends RuntimeException {
    }
}
