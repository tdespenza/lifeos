package com.lifeos.taskgoal.goal;

import com.lifeos.taskgoal.authorization.GoalAuthorizationActions;
import com.lifeos.taskgoal.authorization.GoalAuthorizationResource;
import com.lifeos.taskgoal.authorization.TaskAccessService;
import com.lifeos.taskgoal.authorization.TaskAuthorizationDenied;
import com.lifeos.taskgoal.authorization.TaskSubject;
import com.lifeos.taskgoal.goal.algorithm.DependencyEdge;
import com.lifeos.taskgoal.goal.algorithm.TopologicalSortService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class GoalService {

    private final GoalRepository repository;
    private final TopologicalSortService topologicalSortService;
    private final TaskAccessService accessService;

    public GoalService(
            GoalRepository repository,
            TopologicalSortService topologicalSortService,
            TaskAccessService accessService) {
        this.repository = repository;
        this.topologicalSortService = topologicalSortService;
        this.accessService = accessService;
    }

    public Goal create(TaskSubject subject, String title) {
        UUID goalId = UUID.randomUUID();
        GoalAuthorizationResource resource = GoalAuthorizationResource.forNewGoal(
                goalId, subject.accountId(), subject.tenantId());
        accessService.authorize(subject, GoalAuthorizationActions.CREATE, resource);
        return repository.save(new Goal(goalId, title, subject.accountId(), subject.tenantId()));
    }

    public List<Goal> listAll(TaskSubject subject) {
        accessService.authorize(
                subject,
                GoalAuthorizationActions.LIST,
                GoalAuthorizationResource.forCollection(subject.tenantId()));
        return repository.findAllByOwnerAccountIdAndTenantId(subject.accountId(), subject.tenantId());
    }

    public Goal get(TaskSubject subject, UUID goalId) {
        Optional<Goal> candidate = repository.findById(goalId);
        Goal trustedGoal = candidate.filter(GoalService::hasTrustedOwnership).orElse(null);
        GoalAuthorizationResource resource = Optional.ofNullable(trustedGoal)
                .map(GoalAuthorizationResource::fromGoal)
                .orElseGet(() -> GoalAuthorizationResource.forMissingGoal(goalId, subject.tenantId()));

        accessService.authorize(subject, GoalAuthorizationActions.READ, resource);

        // The identity decision is the policy authority. Keeping a second local owner check here
        // would silently override a scoped role such as TENANT_ADMIN after identity audited an
        // allow. A missing resource still becomes the same generic denial after the decision.
        // A legacy row without ownership facts is treated exactly like a missing row. In
        // particular, an otherwise authorized tenant administrator must not receive a resource
        // whose object-level attributes were never established.
        if (trustedGoal == null) {
            throw new TaskAuthorizationDenied();
        }
        return trustedGoal;
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

}
