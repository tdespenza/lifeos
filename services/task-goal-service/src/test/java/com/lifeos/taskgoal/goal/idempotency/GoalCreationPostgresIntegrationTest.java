package com.lifeos.taskgoal.goal.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifeos.taskgoal.authorization.TaskAccessService;
import com.lifeos.taskgoal.authorization.TaskSubject;
import com.lifeos.taskgoal.goal.Goal;
import com.lifeos.taskgoal.goal.GoalRepository;
import com.lifeos.taskgoal.goal.GoalService;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Exercises the idempotency unique index and row locks against PostgreSQL, not H2 emulation.
 * Docker-less environments skip this class; H2 coverage remains in the regular focused suite.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "identity.workload-token=integration-test-workload-token"
})
class GoalCreationPostgresIntegrationTest {

    private static final String ACCESS_TOKEN_PROOF =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("lifeos_task_goal")
            .withUsername("lifeos")
            .withPassword("test-only-postgres-password");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
    }

    @Autowired
    private GoalService goalService;

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

    private TaskSubject subject;

    @BeforeEach
    void setUp() {
        idempotencyRepository.deleteAll();
        goalRepository.deleteAll();
        subject = new TaskSubject(UUID.randomUUID(), UUID.randomUUID(), "password", ACCESS_TOKEN_PROOF);
    }

    @Test
    void concurrentMatchingCreatesConvergeOnOnePostgresGoal() throws Exception {
        String key = "postgres-concurrent-create-key";
        String title = "Create exactly once in PostgreSQL";
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Goal> first = executor.submit(() -> createAfterStart(ready, start, title, key));
            Future<Goal> second = executor.submit(() -> createAfterStart(ready, start, title, key));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            Goal firstGoal = first.get(15, TimeUnit.SECONDS);
            Goal secondGoal = second.get(15, TimeUnit.SECONDS);

            assertThat(secondGoal.getId()).isEqualTo(firstGoal.getId());
            assertThat(goalRepository.count()).isEqualTo(1);
            assertThat(idempotencyRepository.count()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void matchingRetryCompletesAPostgresPendingReservationWithItsOriginalGoalId() {
        UUID accountId = UUID.randomUUID();
        String tenantId = accountId.toString();
        String key = "postgres-crash-recovery-key";
        String title = "Recover PostgreSQL reservation";
        UUID reservedGoalId = UUID.randomUUID();
        String keyHash = GoalCreationFingerprint.keyHash(key);

        transactions.reserve(
                accountId,
                tenantId,
                keyHash,
                GoalCreationFingerprint.requestFingerprint(title),
                reservedGoalId);

        Goal recovered = idempotencyService.createOrReplay(
                accountId, tenantId, key, title, UUID.randomUUID());

        assertThat(recovered.getId()).isEqualTo(reservedGoalId);
        assertThat(goalRepository.count()).isEqualTo(1);
        assertThat(idempotencyRepository
                        .findByOwnerAccountIdAndTenantIdAndIdempotencyKeyHash(accountId, tenantId, keyHash)
                        .orElseThrow()
                        .getState())
                .isEqualTo(GoalCreationIdempotencyState.COMPLETED);
    }

    private Goal createAfterStart(CountDownLatch ready, CountDownLatch start, String title, String key)
            throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("concurrent PostgreSQL create did not receive its start signal");
        }
        return goalService.create(subject, title, key);
    }
}
