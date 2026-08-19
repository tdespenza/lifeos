package com.lifeos.taskgoal.projection;

import com.lifeos.taskgoal.authorization.GoalAuthorizationActions;
import com.lifeos.taskgoal.authorization.GoalAuthorizationResource;
import com.lifeos.taskgoal.authorization.TaskAccessService;
import com.lifeos.taskgoal.authorization.TaskSubject;
import com.lifeos.taskgoal.config.TaskGoalCertificateProjectionProperties;
import com.lifeos.taskgoal.goal.Goal;
import com.lifeos.taskgoal.goal.GoalRepository;
import com.lifeos.taskgoal.goal.GoalStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Narrow, workload-authenticated projection used by Trust Ledger certificate issuance.
 *
 * <p>Only immutable completion facts are returned. Missing, cross-owner, incomplete, malformed,
 * and unavailable requests share a generic 404 response so this endpoint cannot be used as a
 * goal-existence oracle.
 */
@RestController
public class TaskGoalCertificateProjectionController {

    static final String PATH = "/api/v1/internal/goals/{goalId}/certificate-facts";
    static final String WORKLOAD_IDENTITY = "X-LifeOS-Workload-Identity";
    static final String WORKLOAD_TOKEN = "X-LifeOS-Workload-Token";

    private final GoalRepository goalRepository;
    private final TaskAccessService accessService;
    private final TaskGoalCertificateProjectionProperties properties;

    public TaskGoalCertificateProjectionController(
            GoalRepository goalRepository,
            TaskAccessService accessService,
            TaskGoalCertificateProjectionProperties properties) {
        this.goalRepository = goalRepository;
        this.accessService = accessService;
        this.properties = properties;
    }

    @PostMapping(PATH)
    @Transactional(readOnly = true)
    public ResponseEntity<GoalCertificateFacts> project(
            @PathVariable UUID goalId,
            @RequestHeader(value = WORKLOAD_IDENTITY, required = false) String workloadIdentity,
            @RequestHeader(value = WORKLOAD_TOKEN, required = false) String workloadToken,
            @Valid @RequestBody GoalCertificateProjectionRequest request) {
        if (!properties.configured()
                || !constantTimeEquals(properties.getWorkloadIdentity(), workloadIdentity)
                || !constantTimeEquals(properties.getWorkloadToken(), workloadToken)) {
            return deny();
        }
        try {
            TaskSubject subject = new TaskSubject(
                    request.subjectId(), request.sessionId(), request.authenticationMethod(), request.accessTokenProof());
            Goal goal = goalRepository.findById(goalId).orElse(null);
            GoalAuthorizationResource resource = goal == null
                    ? GoalAuthorizationResource.forMissingGoal(goalId, subject.tenantId())
                    : GoalAuthorizationResource.fromGoal(goal);
            accessService.authorize(subject, GoalAuthorizationActions.READ, resource);
            if (goal == null
                    || goal.getStatus() != GoalStatus.COMPLETED
                    || !subject.accountId().equals(goal.getOwnerAccountId())
                    || !subject.tenantId().equals(goal.getTenantId())
                    || goal.getCompletedAt() == null) {
                return deny();
            }
            return ResponseEntity.ok(new GoalCertificateFacts(goal.getId(), goal.getVersion(), goal.getCompletedAt()));
        } catch (RuntimeException exception) {
            return deny();
        }
    }

    private static ResponseEntity<GoalCertificateFacts> deny() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                actual.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public record GoalCertificateProjectionRequest(
            @NotNull UUID subjectId,
            @NotNull UUID sessionId,
            @NotBlank String authenticationMethod,
            @NotBlank String accessTokenProof) {
    }

    public record GoalCertificateFacts(UUID goalId, long goalVersion, Instant completedAt) {
    }
}
