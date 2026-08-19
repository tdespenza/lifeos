package com.lifeos.taskgoal.goal.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifeos.taskgoal.authorization.TaskAccessService;
import com.lifeos.taskgoal.goal.Goal;
import com.lifeos.taskgoal.goal.GoalRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Verifies recovery from the durable reservation left by a process stop before goal commit. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:goal-idempotency-recovery;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=task-goal-test-password",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration-h2",
    "identity.workload-token=integration-test-workload-token"
})
class GoalCreationIdempotencyRecoveryIntegrationTest {

    @Autowired
    private GoalCreationIdempotencyService idempotencyService;

    @Autowired
    private GoalCreationIdempotencyTransactions transactions;

    @Autowired
    private GoalCreationIdempotencyRepository idempotencyRepository;

    @Autowired
    private GoalRepository goalRepository;

    @MockitoBean
    private TaskAccessService accessService;

    @BeforeEach
    void setUp() {
        idempotencyRepository.deleteAll();
        goalRepository.deleteAll();
    }

    @Test
    void matchingRetryRecoversPendingReservationUsingItsOriginallyAllocatedGoalId() {
        UUID accountId = UUID.randomUUID();
        String tenantId = accountId.toString();
        String key = "crash-recovery-key";
        String title = "Recover a reserved goal";
        UUID reservedGoalId = UUID.randomUUID();
        String keyHash = GoalCreationFingerprint.keyHash(key);

        GoalCreationIdempotency pending = transactions.reserve(
                accountId,
                tenantId,
                keyHash,
                GoalCreationFingerprint.requestFingerprint(title),
                reservedGoalId);

        assertThat(pending.getState()).isEqualTo(GoalCreationIdempotencyState.PENDING);
        assertThat(goalRepository.findById(reservedGoalId)).isEmpty();

        Goal recovered = idempotencyService.createOrReplay(
                accountId, tenantId, key, title, UUID.randomUUID());

        assertThat(recovered.getId()).isEqualTo(reservedGoalId);
        assertThat(recovered.getOwnerAccountId()).isEqualTo(accountId);
        assertThat(recovered.getTenantId()).isEqualTo(tenantId);
        assertThat(goalRepository.count()).isEqualTo(1);
        GoalCreationIdempotency completed = idempotencyRepository
                .findByOwnerAccountIdAndTenantIdAndIdempotencyKeyHash(accountId, tenantId, keyHash)
                .orElseThrow();
        assertThat(completed.getState()).isEqualTo(GoalCreationIdempotencyState.COMPLETED);
        assertThat(completed.getCompletedAt()).isNotNull();
    }
}
