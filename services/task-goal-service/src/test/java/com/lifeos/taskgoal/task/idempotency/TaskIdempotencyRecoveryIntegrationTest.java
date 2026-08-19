package com.lifeos.taskgoal.task.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifeos.taskgoal.authorization.TaskAccessService;
import com.lifeos.taskgoal.authorization.TaskSubject;
import com.lifeos.taskgoal.task.TaskLifecycleResult;
import com.lifeos.taskgoal.task.TaskRepository;
import com.lifeos.taskgoal.task.TaskService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Verifies recovery of a separately committed Task create reservation after an interrupted process. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:task-idempotency-recovery;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=task-goal-test-password",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration-h2",
    "identity.workload-token=integration-test-workload-token"
})
class TaskIdempotencyRecoveryIntegrationTest {

    private static final String ACCESS_TOKEN_PROOF =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskCommandIdempotencyTransactions transactions;

    @Autowired
    private TaskCommandIdempotencyRepository idempotencyRepository;

    @Autowired
    private TaskRepository taskRepository;

    @MockitoBean
    private TaskAccessService accessService;

    private TaskSubject subject;

    @BeforeEach
    void setUp() {
        idempotencyRepository.deleteAll();
        taskRepository.deleteAll();
        subject = new TaskSubject(UUID.randomUUID(), UUID.randomUUID(), "password", ACCESS_TOKEN_PROOF);
    }

    @Test
    void matchingRetryRecoversTheOriginalPreallocatedTaskId() {
        String key = "task-recovery-key";
        String title = "Recover durable reservation";
        UUID reservedTaskId = UUID.randomUUID();
        transactions.reserve(
                subject.accountId(),
                subject.tenantId(),
                TaskCommandOperation.CREATE,
                "create",
                reservedTaskId,
                TaskCommandFingerprint.keyHash(key),
                TaskCommandFingerprint.requestFingerprint(reservedTaskId, TaskCommandOperation.CREATE, null, title),
                null);

        TaskLifecycleResult recovered = taskService.create(subject, title, key);

        assertThat(recovered.id()).isEqualTo(reservedTaskId);
        assertThat(taskRepository.count()).isEqualTo(1L);
        assertThat(idempotencyRepository.count()).isEqualTo(1L);
        assertThat(idempotencyRepository.findAll().getFirst().isCompleted()).isTrue();
    }
}
