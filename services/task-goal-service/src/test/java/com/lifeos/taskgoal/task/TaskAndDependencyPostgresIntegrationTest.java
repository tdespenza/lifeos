package com.lifeos.taskgoal.dependency;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifeos.taskgoal.authorization.TaskAccessService;
import com.lifeos.taskgoal.authorization.TaskSubject;
import com.lifeos.taskgoal.goal.GoalRepository;
import com.lifeos.taskgoal.task.Task;
import com.lifeos.taskgoal.task.TaskLifecycleResult;
import com.lifeos.taskgoal.task.TaskRepository;
import com.lifeos.taskgoal.task.TaskService;
import com.lifeos.taskgoal.task.idempotency.TaskCommandIdempotencyRepository;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
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
 * Exercises the two correctness properties H2 cannot establish: unique-index create convergence
 * and a database row lock preventing concurrent opposite edges from both committing.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "identity.workload-token=integration-test-workload-token"
})
class TaskAndDependencyPostgresIntegrationTest {

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
    private TaskService taskService;

    @Autowired
    private PersistedDependencyService dependencyService;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private GoalRepository goalRepository;

    @Autowired
    private TaskCommandIdempotencyRepository idempotencyRepository;

    @Autowired
    private TaskGoalDependencyRepository dependencyRepository;

    @Autowired
    private TaskGoalDependencyGuardRepository guardRepository;

    @MockitoBean
    private TaskAccessService accessService;

    private TaskSubject subject;

    @BeforeEach
    void setUp() {
        dependencyRepository.deleteAll();
        guardRepository.deleteAll();
        idempotencyRepository.deleteAll();
        taskRepository.deleteAll();
        goalRepository.deleteAll();
        subject = new TaskSubject(UUID.randomUUID(), UUID.randomUUID(), "password", ACCESS_TOKEN_PROOF);
    }

    @Test
    void concurrentMatchingTaskCreatesConvergeOnOnePostgresRowAndReplayReservation() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<TaskLifecycleResult> first = executor.submit(() -> createAfterStart(ready, start));
            Future<TaskLifecycleResult> second = executor.submit(() -> createAfterStart(ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            TaskLifecycleResult firstResult = first.get(15, TimeUnit.SECONDS);
            TaskLifecycleResult secondResult = second.get(15, TimeUnit.SECONDS);
            assertThat(secondResult.id()).isEqualTo(firstResult.id());
            assertThat(taskRepository.count()).isEqualTo(1L);
            assertThat(idempotencyRepository.count()).isEqualTo(1L);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void concurrentOppositeEdgesAllowOnePostgresCommitAndRejectTheCycleBeforeTheOtherCommits() throws Exception {
        Task first = taskRepository.saveAndFlush(new Task(
                UUID.randomUUID(), "First", subject.accountId(), subject.tenantId()));
        Task second = taskRepository.saveAndFlush(new Task(
                UUID.randomUUID(), "Second", subject.accountId(), subject.tenantId()));
        PersistedDependencyNode firstNode = new PersistedDependencyNode(DependencyNodeType.TASK, first.getId());
        PersistedDependencyNode secondNode = new PersistedDependencyNode(DependencyNodeType.TASK, second.getId());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> firstEdge = executor.submit(() -> addAfterStart(ready, start, firstNode, secondNode));
            Future<Boolean> secondEdge = executor.submit(() -> addAfterStart(ready, start, secondNode, firstNode));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int committed = successfulEdgeCount(firstEdge, secondEdge);
            assertThat(committed).isEqualTo(1);
            assertThat(dependencyRepository.count()).isEqualTo(1L);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private TaskLifecycleResult createAfterStart(CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("concurrent create did not receive a start signal");
        }
        return taskService.create(subject, "Create exactly once", "postgres-task-create-key");
    }

    private boolean addAfterStart(
            CountDownLatch ready,
            CountDownLatch start,
            PersistedDependencyNode predecessor,
            PersistedDependencyNode dependent)
            throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("concurrent edge add did not receive a start signal");
        }
        return dependencyService.add(subject, predecessor, dependent);
    }

    private static int successfulEdgeCount(Future<Boolean> first, Future<Boolean> second) throws Exception {
        return edgeOutcome(first) + edgeOutcome(second);
    }

    private static int edgeOutcome(Future<Boolean> future) throws Exception {
        try {
            return future.get(15, TimeUnit.SECONDS) ? 1 : 0;
        } catch (ExecutionException exception) {
            assertThat(exception.getCause()).isInstanceOf(DependencyCycleException.class);
            return 0;
        }
    }
}
