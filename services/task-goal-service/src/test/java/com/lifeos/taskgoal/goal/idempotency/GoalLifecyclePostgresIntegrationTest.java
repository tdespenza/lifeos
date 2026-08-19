package com.lifeos.taskgoal.goal.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifeos.taskgoal.authorization.TaskAccessService;
import com.lifeos.taskgoal.authorization.TaskSubject;
import com.lifeos.taskgoal.goal.Goal;
import com.lifeos.taskgoal.goal.GoalLifecycleResult;
import com.lifeos.taskgoal.goal.GoalRepository;
import com.lifeos.taskgoal.goal.GoalService;
import com.lifeos.taskgoal.goal.GoalStatus;
import com.lifeos.taskgoal.goal.GoalVersionConflictException;
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

/** Exercises lifecycle leases/locks and optimistic versions against PostgreSQL rather than H2. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "identity.workload-token=integration-test-workload-token"
})
class GoalLifecyclePostgresIntegrationTest {

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
    private GoalRepository goalRepository;

    @Autowired
    private GoalCreationIdempotencyRepository creationIdempotencyRepository;

    @Autowired
    private GoalMutationIdempotencyRepository mutationIdempotencyRepository;

    @MockitoBean
    private TaskAccessService accessService;

    private TaskSubject subject;

    @BeforeEach
    void setUp() {
        mutationIdempotencyRepository.deleteAll();
        creationIdempotencyRepository.deleteAll();
        goalRepository.deleteAll();
        subject = new TaskSubject(UUID.randomUUID(), UUID.randomUUID(), "password", ACCESS_TOKEN_PROOF);
    }

    @Test
    void matchingConcurrentCompleteCallsConvergeOnOnePostgresTransitionAndReplaySnapshot() throws Exception {
        Goal goal = goalRepository.saveAndFlush(new Goal(
                UUID.randomUUID(), "Finish lifecycle support", subject.accountId(), subject.tenantId()));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<GoalLifecycleResult> first = executor.submit(() -> completeAfterStart(ready, start, goal.getId()));
            Future<GoalLifecycleResult> second = executor.submit(() -> completeAfterStart(ready, start, goal.getId()));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            GoalLifecycleResult firstResult = first.get(15, TimeUnit.SECONDS);
            GoalLifecycleResult secondResult = second.get(15, TimeUnit.SECONDS);

            assertThat(secondResult).isEqualTo(firstResult);
            assertThat(firstResult.status()).isEqualTo(GoalStatus.COMPLETED);
            assertThat(firstResult.version()).isEqualTo(1L);
            Goal persisted = goalRepository.findById(goal.getId()).orElseThrow();
            assertThat(persisted.getStatus()).isEqualTo(GoalStatus.COMPLETED);
            assertThat(persisted.getVersion()).isEqualTo(1L);
            assertThat(mutationIdempotencyRepository.count()).isEqualTo(1L);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void differentlyKeyedConcurrentUpdatesAtTheSameVersionAllowOnlyOnePostgresWrite() throws Exception {
        Goal goal = goalRepository.saveAndFlush(new Goal(
                UUID.randomUUID(), "Before", subject.accountId(), subject.tenantId()));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<GoalLifecycleResult> winner = executor.submit(() -> updateAfterStart(
                    ready, start, goal.getId(), "First title", "first-update-key"));
            Future<GoalLifecycleResult> loser = executor.submit(() -> updateAfterStart(
                    ready, start, goal.getId(), "Second title", "second-update-key"));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            GoalLifecycleResult firstOutcome = outcome(winner);
            GoalLifecycleResult secondOutcome = outcome(loser);

            assertThat(ListOutcome.successCount(firstOutcome, secondOutcome)).isEqualTo(1);
            assertThat(ListOutcome.versionConflictCount(firstOutcome, secondOutcome)).isEqualTo(1);
            Goal persisted = goalRepository.findById(goal.getId()).orElseThrow();
            assertThat(persisted.getVersion()).isEqualTo(1L);
            assertThat(persisted.getTitle()).isIn("First title", "Second title");
            assertThat(mutationIdempotencyRepository.count()).isEqualTo(2L);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private GoalLifecycleResult completeAfterStart(CountDownLatch ready, CountDownLatch start, UUID goalId)
            throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("concurrent completion did not receive its start signal");
        }
        return goalService.complete(subject, goalId, 0L, "same-complete-key");
    }

    private GoalLifecycleResult updateAfterStart(
            CountDownLatch ready, CountDownLatch start, UUID goalId, String title, String key) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("concurrent update did not receive its start signal");
        }
        return goalService.update(subject, goalId, 0L, title, key);
    }

    private GoalLifecycleResult outcome(Future<GoalLifecycleResult> future) throws Exception {
        try {
            return future.get(15, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException exception) {
            assertThat(exception.getCause()).isInstanceOf(GoalVersionConflictException.class);
            return null;
        }
    }

    /** Small assertion helper that keeps the concurrent result shape explicit. */
    private static final class ListOutcome {

        private ListOutcome() {
        }

        private static int successCount(GoalLifecycleResult first, GoalLifecycleResult second) {
            return (first == null ? 0 : 1) + (second == null ? 0 : 1);
        }

        private static int versionConflictCount(GoalLifecycleResult first, GoalLifecycleResult second) {
            return (first == null ? 1 : 0) + (second == null ? 1 : 0);
        }
    }
}
