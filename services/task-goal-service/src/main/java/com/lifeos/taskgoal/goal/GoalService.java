package com.lifeos.taskgoal.goal;

import com.lifeos.taskgoal.authorization.GoalAuthorizationActions;
import com.lifeos.taskgoal.authorization.GoalAuthorizationResource;
import com.lifeos.taskgoal.authorization.TaskAccessService;
import com.lifeos.taskgoal.authorization.TaskAuthorizationDenied;
import com.lifeos.taskgoal.authorization.TaskSubject;
import com.lifeos.taskgoal.goal.algorithm.DependencyEdge;
import com.lifeos.taskgoal.goal.algorithm.TopologicalSortService;
import com.lifeos.taskgoal.goal.idempotency.GoalCreationIdempotencyService;
import com.lifeos.taskgoal.goal.idempotency.GoalMutationIdempotencyService;
import com.lifeos.taskgoal.goal.idempotency.GoalMutationOperation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class GoalService {

    private final GoalRepository repository;
    private final TopologicalSortService topologicalSortService;
    private final TaskAccessService accessService;
    private final GoalCreationIdempotencyService idempotencyService;
    private final GoalMutationIdempotencyService mutationIdempotencyService;

    public GoalService(
            GoalRepository repository,
            TopologicalSortService topologicalSortService,
            TaskAccessService accessService,
            GoalCreationIdempotencyService idempotencyService,
            GoalMutationIdempotencyService mutationIdempotencyService) {
        this.repository = repository;
        this.topologicalSortService = topologicalSortService;
        this.accessService = accessService;
        this.idempotencyService = idempotencyService;
        this.mutationIdempotencyService = mutationIdempotencyService;
    }

    /**
     * Creates a goal once for a bounded client idempotency key, or returns that goal on a matching
     * replay. Authorization is intentionally evaluated for every HTTP submission, including a
     * replay, so a revoked or newly denied subject cannot recover a prior response.
     */
    public Goal create(TaskSubject subject, String title, String idempotencyKey) {
        UUID goalId = UUID.randomUUID();
        GoalAuthorizationResource resource = GoalAuthorizationResource.forNewGoal(
                goalId, subject.accountId(), subject.tenantId());
        accessService.authorize(subject, GoalAuthorizationActions.CREATE, resource);
        return idempotencyService.createOrReplay(
                subject.accountId(), subject.tenantId(), idempotencyKey, title, goalId);
    }

    public Goal create(
            TaskSubject subject, String title, Integer priority, java.time.Instant dueAt, String idempotencyKey) {
        UUID goalId = UUID.randomUUID();
        GoalAuthorizationResource resource = GoalAuthorizationResource.forNewGoal(
                goalId, subject.accountId(), subject.tenantId());
        accessService.authorize(subject, GoalAuthorizationActions.CREATE, resource);
        return idempotencyService.createOrReplay(
                subject.accountId(), subject.tenantId(), idempotencyKey, title, priority == null ? 3 : priority, dueAt, goalId);
    }

    public List<Goal> listAll(TaskSubject subject) {
        accessService.authorize(
                subject,
                GoalAuthorizationActions.LIST,
                GoalAuthorizationResource.forCollection(subject.tenantId()));
        return repository.findAllByOwnerAccountIdAndTenantId(subject.accountId(), subject.tenantId());
    }

    public Goal get(TaskSubject subject, UUID goalId) {
        return loadAuthorizedGoal(subject, goalId, GoalAuthorizationActions.READ);
    }

    /** Updates only the title of an active goal and returns an immutable idempotent result. */
    public GoalLifecycleResult update(
            TaskSubject subject, UUID goalId, long expectedVersion, String title, String idempotencyKey) {
        Goal goal = loadAuthorizedGoal(subject, goalId, GoalAuthorizationActions.UPDATE);
        return mutationIdempotencyService.execute(
                subject.accountId(),
                goal.getTenantId(),
                goal,
                GoalMutationOperation.UPDATE,
                expectedVersion,
                idempotencyKey,
                title);
    }

    public GoalLifecycleResult update(
            TaskSubject subject,
            UUID goalId,
            long expectedVersion,
            String title,
            Integer priority,
            java.time.Instant dueAt,
            String idempotencyKey) {
        Goal goal = loadAuthorizedGoal(subject, goalId, GoalAuthorizationActions.UPDATE);
        return mutationIdempotencyService.execute(
                subject.accountId(),
                goal.getTenantId(),
                goal,
                GoalMutationOperation.UPDATE,
                expectedVersion,
                idempotencyKey,
                title,
                priority == null ? goal.getPriority() : priority,
                dueAt == null ? goal.getDueAt() : dueAt);
    }

    /** Completes an active goal exactly once for a caller-scoped idempotency request. */
    public GoalLifecycleResult complete(
            TaskSubject subject, UUID goalId, long expectedVersion, String idempotencyKey) {
        Goal goal = loadAuthorizedGoal(subject, goalId, GoalAuthorizationActions.COMPLETE);
        return mutationIdempotencyService.execute(
                subject.accountId(),
                goal.getTenantId(),
                goal,
                GoalMutationOperation.COMPLETE,
                expectedVersion,
                idempotencyKey,
                null);
    }

    /** Archives an active or completed goal exactly once for a caller-scoped idempotency request. */
    public GoalLifecycleResult archive(
            TaskSubject subject, UUID goalId, long expectedVersion, String idempotencyKey) {
        Goal goal = loadAuthorizedGoal(subject, goalId, GoalAuthorizationActions.ARCHIVE);
        return mutationIdempotencyService.execute(
                subject.accountId(),
                goal.getTenantId(),
                goal,
                GoalMutationOperation.ARCHIVE,
                expectedVersion,
                idempotencyKey,
                null);
    }

    public List<String> resolveDependencyOrder(
            TaskSubject subject, List<String> goals, List<DependencyEdge> dependencies) {
        accessService.authorize(
                subject,
                GoalAuthorizationActions.DEPENDENCY_ORDER,
                GoalAuthorizationResource.forCollection(subject.tenantId()));
        return topologicalSortService.order(goals, dependencies);
    }

    private static boolean hasTrustedOwnership(Goal goal) {
        return goal.getOwnerAccountId() != null && goal.getTenantId() != null && !goal.getTenantId().isBlank();
    }

    /**
     * Loads trusted object facts before every object action, including lifecycle commands.
     *
     * <p>A missing, ownerless legacy, and cross-user goal take the same authorization path and
     * public deny shape. Identity remains the policy authority for a scoped tenant administrator;
     * this service only refuses a missing or ownership-unknown resource after that decision.
     */
    private Goal loadAuthorizedGoal(TaskSubject subject, UUID goalId, String action) {
        Optional<Goal> candidate = repository.findById(goalId);
        Goal trustedGoal = candidate.filter(GoalService::hasTrustedOwnership).orElse(null);
        GoalAuthorizationResource resource = Optional.ofNullable(trustedGoal)
                .map(GoalAuthorizationResource::fromGoal)
                .orElseGet(() -> GoalAuthorizationResource.forMissingGoal(goalId, subject.tenantId()));
        accessService.authorize(subject, action, resource);
        if (trustedGoal == null) {
            throw new TaskAuthorizationDenied();
        }
        return trustedGoal;
    }

}
