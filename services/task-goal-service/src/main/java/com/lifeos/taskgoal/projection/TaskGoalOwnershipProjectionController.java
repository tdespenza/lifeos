package com.lifeos.taskgoal.projection;

import com.lifeos.taskgoal.authorization.GoalAuthorizationActions;
import com.lifeos.taskgoal.authorization.GoalAuthorizationResource;
import com.lifeos.taskgoal.authorization.TaskAccessService;
import com.lifeos.taskgoal.authorization.TaskAuthorizationActions;
import com.lifeos.taskgoal.authorization.TaskAuthorizationResource;
import com.lifeos.taskgoal.authorization.TaskGoalAuthorizationResource;
import com.lifeos.taskgoal.authorization.TaskSubject;
import com.lifeos.taskgoal.config.TaskGoalProjectionProperties;
import com.lifeos.taskgoal.goal.Goal;
import com.lifeos.taskgoal.goal.GoalRepository;
import com.lifeos.taskgoal.task.Task;
import com.lifeos.taskgoal.task.TaskRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * No-disclosure ownership projection for Calendar's linked Task/Goal blocks.
 *
 * <p>The endpoint accepts only an already validated subject proof from Calendar and a workload
 * credential. It reauthorizes the exact object through Identity using locally loaded ownership
 * facts, then returns either an empty 204 or the same generic 404 for every denial/unavailable
 * path. It never returns a task/goal representation or copied ownership data.
 */
@RestController
public class TaskGoalOwnershipProjectionController {

    static final String PATH = "/api/v1/internal/planning/ownership-proof";
    static final String PLANNING_PATH = "/api/v1/internal/planning/priority-projection";
    static final String WORKLOAD_IDENTITY = "X-LifeOS-Workload-Identity";
    static final String WORKLOAD_TOKEN = "X-LifeOS-Workload-Token";
    private static final UUID ZERO = new UUID(0L, 0L);

    private final TaskRepository taskRepository;
    private final GoalRepository goalRepository;
    private final TaskAccessService accessService;
    private final TaskGoalProjectionProperties properties;

    public TaskGoalOwnershipProjectionController(
            TaskRepository taskRepository,
            GoalRepository goalRepository,
            TaskAccessService accessService,
            TaskGoalProjectionProperties properties) {
        this.taskRepository = taskRepository;
        this.goalRepository = goalRepository;
        this.accessService = accessService;
        this.properties = properties;
    }

    @PostMapping(PATH)
    @Transactional(readOnly = true)
    public ResponseEntity<Void> verify(
            @RequestHeader(value = WORKLOAD_IDENTITY, required = false) String workloadIdentity,
            @RequestHeader(value = WORKLOAD_TOKEN, required = false) String workloadToken,
            @Valid @RequestBody OwnershipProjectionRequest request) {
        if (!properties.configured()
                || !constantTimeEquals(properties.getWorkloadIdentity(), workloadIdentity)
                || !constantTimeEquals(properties.getWorkloadToken(), workloadToken)) {
            return genericDeny();
        }
        try {
            UUID resourceId = UUID.fromString(request.resourceId());
            TaskSubject subject = new TaskSubject(
                    request.subjectId(), request.sessionId(), request.authenticationMethod(), request.accessTokenProof());
            TaskGoalAuthorizationResource resource;
            String action;
            if ("TASK".equals(request.resourceType())) {
                Task task = taskRepository.findById(resourceId).orElse(null);
                resource = task == null
                        ? TaskAuthorizationResource.forMissingTask(resourceId, subject.tenantId())
                        : TaskAuthorizationResource.fromTask(task);
                action = TaskAuthorizationActions.READ;
                if (task == null || !ownedBy(task.getOwnerAccountId(), task.getTenantId(), subject)) {
                    accessService.authorize(subject, action, resource);
                    return genericDeny();
                }
            } else if ("GOAL".equals(request.resourceType())) {
                Goal goal = goalRepository.findById(resourceId).orElse(null);
                resource = goal == null
                        ? GoalAuthorizationResource.forMissingGoal(resourceId, subject.tenantId())
                        : GoalAuthorizationResource.fromGoal(goal);
                action = GoalAuthorizationActions.READ;
                if (goal == null || !ownedBy(goal.getOwnerAccountId(), goal.getTenantId(), subject)) {
                    accessService.authorize(subject, action, resource);
                    return genericDeny();
                }
            } else {
                return genericDeny();
            }
            accessService.authorize(subject, action, resource);
            return ResponseEntity.noContent().build();
        } catch (RuntimeException exception) {
            // Authorization dependency failures, malformed proofs, and missing resources are
            // deliberately indistinguishable to the caller.
            return genericDeny();
        }
    }

    /**
     * Returns only bounded planning facts after the same workload and exact owner authorization
     * used by the no-disclosure ownership proof. Calendar never supplies ownership facts; this
     * service reloads them from its own database and returns no body on any denial.
     */
    @PostMapping(PLANNING_PATH)
    @Transactional(readOnly = true)
    public ResponseEntity<TaskGoalPlanningProjectionResponse> project(
            @RequestHeader(value = WORKLOAD_IDENTITY, required = false) String workloadIdentity,
            @RequestHeader(value = WORKLOAD_TOKEN, required = false) String workloadToken,
            @Valid @RequestBody TaskGoalPlanningProjectionRequest request) {
        if (!properties.configured()
                || !constantTimeEquals(properties.getWorkloadIdentity(), workloadIdentity)
                || !constantTimeEquals(properties.getWorkloadToken(), workloadToken)) {
            return planningDeny();
        }
        try {
            UUID resourceId = UUID.fromString(request.resourceId());
            TaskSubject subject = new TaskSubject(
                    request.subjectId(), request.sessionId(), request.authenticationMethod(), request.accessTokenProof());
            if ("TASK".equals(request.resourceType())) {
                Task task = taskRepository.findById(resourceId).orElse(null);
                TaskAuthorizationResource resource = task == null
                        ? TaskAuthorizationResource.forMissingTask(resourceId, subject.tenantId())
                        : TaskAuthorizationResource.fromTask(task);
                accessService.authorize(subject, TaskAuthorizationActions.READ, resource);
                if (task == null || !ownedBy(task.getOwnerAccountId(), task.getTenantId(), subject)) {
                    return planningDeny();
                }
                return ResponseEntity.ok(new TaskGoalPlanningProjectionResponse(
                        "TASK", task.getId(), task.getPriority(), task.getDueAt()));
            }
            if ("GOAL".equals(request.resourceType())) {
                Goal goal = goalRepository.findById(resourceId).orElse(null);
                GoalAuthorizationResource resource = goal == null
                        ? GoalAuthorizationResource.forMissingGoal(resourceId, subject.tenantId())
                        : GoalAuthorizationResource.fromGoal(goal);
                accessService.authorize(subject, GoalAuthorizationActions.READ, resource);
                if (goal == null || !ownedBy(goal.getOwnerAccountId(), goal.getTenantId(), subject)) {
                    return planningDeny();
                }
                return ResponseEntity.ok(new TaskGoalPlanningProjectionResponse(
                        "GOAL", goal.getId(), goal.getPriority(), goal.getDueAt()));
            }
            return planningDeny();
        } catch (RuntimeException exception) {
            return planningDeny();
        }
    }

    private static boolean ownedBy(UUID owner, String tenant, TaskSubject subject) {
        return owner != null && tenant != null && owner.equals(subject.accountId()) && tenant.equals(subject.tenantId());
    }

    private static ResponseEntity<Void> genericDeny() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }

    private static ResponseEntity<TaskGoalPlanningProjectionResponse> planningDeny() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        byte[] left = expected.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] right = actual.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(left, right);
    }

    /** Internal proof envelope; it is never serialized in a public response or audit record. */
    public record OwnershipProjectionRequest(
            @NotNull UUID subjectId,
            @NotNull UUID sessionId,
            @NotBlank String authenticationMethod,
            @NotBlank String accessTokenProof,
            @NotBlank String resourceType,
            @NotBlank String resourceId) {
    }
}
