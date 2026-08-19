package com.lifeos.taskgoal.goal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GoalServiceTest {

    private static final String ACCESS_TOKEN_PROOF =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Mock
    private GoalRepository repository;

    @Mock
    private TopologicalSortService topologicalSortService;

    @Mock
    private TaskAccessService accessService;

    @Mock
    private GoalCreationIdempotencyService idempotencyService;

    @Mock
    private GoalMutationIdempotencyService mutationIdempotencyService;

    private GoalService service;
    private TaskSubject subject;

    @BeforeEach
    void setUp() {
        service = new GoalService(
                repository,
                topologicalSortService,
                accessService,
                idempotencyService,
                mutationIdempotencyService);
        subject = new TaskSubject(UUID.randomUUID(), UUID.randomUUID(), "password", ACCESS_TOKEN_PROOF);
    }

    @Test
    void createAuthorizesAndDelegatesToScopeBoundIdempotencyBeforePersistence() {
        Goal persistedGoal = new Goal(
                UUID.randomUUID(), "Prepare architecture review", subject.accountId(), subject.tenantId());
        when(idempotencyService.createOrReplay(
                        eq(subject.accountId()),
                        eq(subject.tenantId()),
                        eq("goal-create-key"),
                        eq("Prepare architecture review"),
                        any(UUID.class)))
                .thenReturn(persistedGoal);

        Goal created = service.create(subject, "Prepare architecture review", "goal-create-key");

        assertThat(created.getOwnerAccountId()).isEqualTo(subject.accountId());
        assertThat(created.getTenantId()).isEqualTo(subject.tenantId());
        ArgumentCaptor<GoalAuthorizationResource> resource = ArgumentCaptor.forClass(GoalAuthorizationResource.class);
        ArgumentCaptor<UUID> proposedGoalId = ArgumentCaptor.forClass(UUID.class);
        verify(accessService).authorize(eq(subject), eq(GoalAuthorizationActions.CREATE), resource.capture());
        verify(idempotencyService).createOrReplay(
                eq(subject.accountId()),
                eq(subject.tenantId()),
                eq("goal-create-key"),
                eq("Prepare architecture review"),
                proposedGoalId.capture());
        assertThat(resource.getValue().resourceId()).isEqualTo(proposedGoalId.getValue().toString());
        assertThat(resource.getValue().tenantId()).isEqualTo(subject.tenantId());
        assertThat(resource.getValue().attributes())
                .containsExactlyEntriesOf(java.util.Map.of("ownerAccountId", subject.accountId().toString()));
    }

    @Test
    void createDoesNotReserveAnIdempotencyKeyWhenAuthorizationDeniesTheMutation() {
        doThrow(new TaskAuthorizationDenied())
                .when(accessService)
                .authorize(eq(subject), eq(GoalAuthorizationActions.CREATE), any(GoalAuthorizationResource.class));

        assertThatThrownBy(() -> service.create(subject, "Denied goal", "denied-goal-key"))
                .isInstanceOf(TaskAuthorizationDenied.class);

        verifyNoInteractions(idempotencyService);
    }

    @Test
    void listUsesOwnerAndTenantScopedRepositoryQuery() {
        Goal ownedGoal = new Goal(UUID.randomUUID(), "Owned", subject.accountId(), subject.tenantId());
        when(repository.findAllByOwnerAccountIdAndTenantId(subject.accountId(), subject.tenantId()))
                .thenReturn(List.of(ownedGoal));

        List<Goal> goals = service.listAll(subject);

        assertThat(goals).containsExactly(ownedGoal);
        ArgumentCaptor<GoalAuthorizationResource> resource = ArgumentCaptor.forClass(GoalAuthorizationResource.class);
        verify(accessService).authorize(eq(subject), eq(GoalAuthorizationActions.LIST), resource.capture());
        assertThat(resource.getValue().resourceId()).isNull();
        assertThat(resource.getValue().tenantId()).isEqualTo(subject.tenantId());
        assertThat(resource.getValue().attributes()).isEmpty();
        verify(repository).findAllByOwnerAccountIdAndTenantId(subject.accountId(), subject.tenantId());
    }

    @Test
    void missingGoalStillCallsIdentityThenReturnsGenericDenial() {
        UUID missingGoalId = UUID.randomUUID();
        when(repository.findById(missingGoalId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(subject, missingGoalId)).isInstanceOf(TaskAuthorizationDenied.class);

        ArgumentCaptor<GoalAuthorizationResource> resource = ArgumentCaptor.forClass(GoalAuthorizationResource.class);
        verify(accessService).authorize(eq(subject), eq(GoalAuthorizationActions.READ), resource.capture());
        assertThat(resource.getValue().resourceId()).isEqualTo(missingGoalId.toString());
        assertThat(resource.getValue().tenantId()).isEqualTo(subject.tenantId());
        assertThat(resource.getValue().attributes()).containsExactlyEntriesOf(java.util.Map.of(
                "ownerAccountId", "00000000-0000-0000-0000-000000000000",
                "resourceExists", "false"));
    }

    @Test
    void legacyGoalWithoutTrustedOwnershipIsDeniedEvenWhenPolicyAllows() {
        UUID legacyGoalId = UUID.randomUUID();
        Goal legacyGoal = mock(Goal.class);
        when(repository.findById(legacyGoalId)).thenReturn(Optional.of(legacyGoal));

        assertThatThrownBy(() -> service.get(subject, legacyGoalId)).isInstanceOf(TaskAuthorizationDenied.class);

        verify(accessService).authorize(
                subject,
                GoalAuthorizationActions.READ,
                GoalAuthorizationResource.forMissingGoal(legacyGoalId, subject.tenantId()));
    }

    @Test
    void crossUserGoalIsDeniedWhenTheIdentityPolicyDeniesIt() {
        UUID crossUserGoalId = UUID.randomUUID();
        Goal otherUsersGoal = new Goal(
                crossUserGoalId, "Other account", UUID.randomUUID(), UUID.randomUUID().toString());
        when(repository.findById(crossUserGoalId)).thenReturn(Optional.of(otherUsersGoal));
        doThrow(new TaskAuthorizationDenied())
                .when(accessService)
                .authorize(eq(subject), eq(GoalAuthorizationActions.READ), any(GoalAuthorizationResource.class));

        assertThatThrownBy(() -> service.get(subject, crossUserGoalId)).isInstanceOf(TaskAuthorizationDenied.class);

        verify(accessService).authorize(
                subject,
                GoalAuthorizationActions.READ,
                GoalAuthorizationResource.fromGoal(otherUsersGoal));
    }

    @Test
    void readAllowsCallerOwnedGoalAfterIdentityAllowDecision() {
        UUID goalId = UUID.randomUUID();
        Goal ownedGoal = new Goal(goalId, "Owned", subject.accountId(), subject.tenantId());
        when(repository.findById(goalId)).thenReturn(Optional.of(ownedGoal));

        Goal result = service.get(subject, goalId);

        assertThat(result).isSameAs(ownedGoal);
        verify(accessService).authorize(
                subject, GoalAuthorizationActions.READ, GoalAuthorizationResource.fromGoal(ownedGoal));
    }

    @Test
    void updateAuthorizesTrustedGoalThenDelegatesWithActorAndResourceTenantScope() {
        UUID goalId = UUID.randomUUID();
        Goal ownedGoal = new Goal(goalId, "Original", subject.accountId(), subject.tenantId());
        when(repository.findById(goalId)).thenReturn(Optional.of(ownedGoal));
        GoalLifecycleResult result = GoalLifecycleResult.from(ownedGoal);
        when(mutationIdempotencyService.execute(
                        subject.accountId(),
                        subject.tenantId(),
                        ownedGoal,
                        GoalMutationOperation.UPDATE,
                        0L,
                        "goal-update-key",
                        "Renamed"))
                .thenReturn(result);

        assertThat(service.update(subject, goalId, 0L, "Renamed", "goal-update-key")).isEqualTo(result);

        verify(accessService).authorize(
                subject, GoalAuthorizationActions.UPDATE, GoalAuthorizationResource.fromGoal(ownedGoal));
        verify(mutationIdempotencyService).execute(
                subject.accountId(),
                subject.tenantId(),
                ownedGoal,
                GoalMutationOperation.UPDATE,
                0L,
                "goal-update-key",
                "Renamed");
    }

    @Test
    void completeAndArchiveUseDistinctObjectPolicyActions() {
        UUID goalId = UUID.randomUUID();
        Goal ownedGoal = new Goal(goalId, "Lifecycle", subject.accountId(), subject.tenantId());
        when(repository.findById(goalId)).thenReturn(Optional.of(ownedGoal));
        GoalLifecycleResult result = GoalLifecycleResult.from(ownedGoal);
        when(mutationIdempotencyService.execute(
                        subject.accountId(),
                        subject.tenantId(),
                        ownedGoal,
                        GoalMutationOperation.COMPLETE,
                        0L,
                        "goal-complete-key",
                        null))
                .thenReturn(result);
        when(mutationIdempotencyService.execute(
                        subject.accountId(),
                        subject.tenantId(),
                        ownedGoal,
                        GoalMutationOperation.ARCHIVE,
                        0L,
                        "goal-archive-key",
                        null))
                .thenReturn(result);

        assertThat(service.complete(subject, goalId, 0L, "goal-complete-key")).isEqualTo(result);
        assertThat(service.archive(subject, goalId, 0L, "goal-archive-key")).isEqualTo(result);

        verify(accessService).authorize(
                subject, GoalAuthorizationActions.COMPLETE, GoalAuthorizationResource.fromGoal(ownedGoal));
        verify(accessService).authorize(
                subject, GoalAuthorizationActions.ARCHIVE, GoalAuthorizationResource.fromGoal(ownedGoal));
    }

    @Test
    void missingLifecycleTargetStillCallsIdentityThenReturnsGenericDenialWithoutReservingAKey() {
        UUID missingGoalId = UUID.randomUUID();
        when(repository.findById(missingGoalId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.complete(subject, missingGoalId, 0L, "missing-goal-key"))
                .isInstanceOf(TaskAuthorizationDenied.class);

        verify(accessService).authorize(
                subject,
                GoalAuthorizationActions.COMPLETE,
                GoalAuthorizationResource.forMissingGoal(missingGoalId, subject.tenantId()));
        verifyNoInteractions(mutationIdempotencyService);
    }

    @Test
    void dependencyOrderingUsesTenantOnlyCollectionResource() {
        List<String> goals = List.of("Plan", "Build");
        List<DependencyEdge> dependencies = List.of();
        when(topologicalSortService.order(goals, dependencies)).thenReturn(goals);

        List<String> result = service.resolveDependencyOrder(subject, goals, dependencies);

        assertThat(result).isEqualTo(goals);
        ArgumentCaptor<GoalAuthorizationResource> resource = ArgumentCaptor.forClass(GoalAuthorizationResource.class);
        verify(accessService).authorize(eq(subject), eq(GoalAuthorizationActions.DEPENDENCY_ORDER), resource.capture());
        assertThat(resource.getValue().resourceId()).isNull();
        assertThat(resource.getValue().tenantId()).isEqualTo(subject.tenantId());
        assertThat(resource.getValue().attributes()).isEmpty();
        verify(topologicalSortService).order(goals, dependencies);
    }
}
