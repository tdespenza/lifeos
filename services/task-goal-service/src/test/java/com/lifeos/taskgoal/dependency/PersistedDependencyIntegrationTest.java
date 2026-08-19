package com.lifeos.taskgoal.dependency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeos.taskgoal.authorization.TaskAccessService;
import com.lifeos.taskgoal.authorization.TaskAuthorizationActions;
import com.lifeos.taskgoal.authorization.TaskSubject;
import com.lifeos.taskgoal.goal.Goal;
import com.lifeos.taskgoal.goal.GoalRepository;
import com.lifeos.taskgoal.task.Task;
import com.lifeos.taskgoal.task.TaskRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** HTTP and persistence coverage for owner-scoped mixed Task/Goal dependency graphs. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:persisted-dependency;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=task-goal-test-password",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration-h2",
    "identity.workload-token=integration-test-workload-token"
})
@AutoConfigureMockMvc
class PersistedDependencyIntegrationTest {

    private static final String BEARER = "Bearer dependency-test-token";
    private static final String ACCESS_TOKEN_PROOF =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private GoalRepository goalRepository;

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
        taskRepository.deleteAll();
        goalRepository.deleteAll();
        reset(accessService);
        subject = new TaskSubject(UUID.randomUUID(), UUID.randomUUID(), "password", ACCESS_TOKEN_PROOF);
        when(accessService.authenticate(anyString())).thenReturn(subject);
    }

    @Test
    void persistsMixedEdgesWithIdempotentCreateRemoveAndDeterministicExecutionOrder() throws Exception {
        Goal goal = goalRepository.saveAndFlush(new Goal(
                UUID.randomUUID(), "Plan", subject.accountId(), subject.tenantId()));
        Task firstTask = taskRepository.saveAndFlush(new Task(
                UUID.randomUUID(), "Build", subject.accountId(), subject.tenantId()));
        Task secondTask = taskRepository.saveAndFlush(new Task(
                UUID.randomUUID(), "Ship", subject.accountId(), subject.tenantId()));

        putDependency(DependencyNodeType.TASK, firstTask.getId(), DependencyNodeType.GOAL, goal.getId())
                .andExpect(status().isNoContent());
        putDependency(DependencyNodeType.TASK, secondTask.getId(), DependencyNodeType.TASK, firstTask.getId())
                .andExpect(status().isNoContent());
        // A matching duplicate PUT is a set operation, not a duplicate write.
        putDependency(DependencyNodeType.TASK, secondTask.getId(), DependencyNodeType.TASK, firstTask.getId())
                .andExpect(status().isNoContent());
        assertThat(dependencyRepository.count()).isEqualTo(2L);

        MvcResult ordered = mockMvc.perform(get("/api/v1/dependencies/execution-order")
                        .header("Authorization", BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.length()").value(3))
                .andReturn();
        JsonNode order = objectMapper.readTree(ordered.getResponse().getContentAsString()).path("order");
        assertThat(indexOf(order, goal.getId())).isLessThan(indexOf(order, firstTask.getId()));
        assertThat(indexOf(order, firstTask.getId())).isLessThan(indexOf(order, secondTask.getId()));

        deleteDependency(DependencyNodeType.TASK, secondTask.getId(), DependencyNodeType.TASK, firstTask.getId())
                .andExpect(status().isNoContent());
        deleteDependency(DependencyNodeType.TASK, secondTask.getId(), DependencyNodeType.TASK, firstTask.getId())
                .andExpect(status().isNoContent());
        assertThat(dependencyRepository.count()).isEqualTo(1L);

        verify(accessService, org.mockito.Mockito.times(5))
                .authorize(eq(subject), eq(TaskAuthorizationActions.DEPENDENCY_MANAGE), org.mockito.ArgumentMatchers.any());
        verify(accessService)
                .authorize(eq(subject), eq(TaskAuthorizationActions.DEPENDENCY_ORDER), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsSelfAndCycleBeforeCommitWithoutReturningAPartialOrder() throws Exception {
        Task first = taskRepository.saveAndFlush(new Task(
                UUID.randomUUID(), "First", subject.accountId(), subject.tenantId()));
        Task second = taskRepository.saveAndFlush(new Task(
                UUID.randomUUID(), "Second", subject.accountId(), subject.tenantId()));

        putDependency(DependencyNodeType.TASK, first.getId(), DependencyNodeType.TASK, first.getId())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("A dependency cannot reference the same node"));
        putDependency(DependencyNodeType.TASK, second.getId(), DependencyNodeType.TASK, first.getId())
                .andExpect(status().isNoContent());
        putDependency(DependencyNodeType.TASK, first.getId(), DependencyNodeType.TASK, second.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Dependency would create a cycle"));
        assertThat(dependencyRepository.count()).isEqualTo(1L);
    }

    @Test
    void missingAndCrossUserNodesRemainIndistinguishable() throws Exception {
        Task owned = taskRepository.saveAndFlush(new Task(
                UUID.randomUUID(), "Owned", subject.accountId(), subject.tenantId()));
        Task foreign = taskRepository.saveAndFlush(new Task(
                UUID.randomUUID(), "Confidential", UUID.randomUUID(), UUID.randomUUID().toString()));

        MvcResult crossUser = putDependency(DependencyNodeType.TASK, owned.getId(), DependencyNodeType.TASK, foreign.getId())
                .andExpect(status().isForbidden())
                .andReturn();
        MvcResult missing = putDependency(DependencyNodeType.TASK, owned.getId(), DependencyNodeType.TASK, UUID.randomUUID())
                .andExpect(status().isForbidden())
                .andReturn();

        assertThat(missing.getResponse().getContentAsString())
                .isEqualTo(crossUser.getResponse().getContentAsString())
                .doesNotContain(foreign.getId().toString())
                .doesNotContain(foreign.getTitle());
        assertThat(dependencyRepository.count()).isZero();
    }

    private org.springframework.test.web.servlet.ResultActions putDependency(
            DependencyNodeType dependentType,
            UUID dependentId,
            DependencyNodeType predecessorType,
            UUID predecessorId)
            throws Exception {
        return mockMvc.perform(put("/api/v1/dependencies/{dependentType}/{dependentId}/depends-on/{predecessorType}/{predecessorId}",
                        dependentType, dependentId, predecessorType, predecessorId)
                .header("Authorization", BEARER));
    }

    private org.springframework.test.web.servlet.ResultActions deleteDependency(
            DependencyNodeType dependentType,
            UUID dependentId,
            DependencyNodeType predecessorType,
            UUID predecessorId)
            throws Exception {
        return mockMvc.perform(delete("/api/v1/dependencies/{dependentType}/{dependentId}/depends-on/{predecessorType}/{predecessorId}",
                        dependentType, dependentId, predecessorType, predecessorId)
                .header("Authorization", BEARER));
    }

    private static int indexOf(JsonNode order, UUID id) {
        for (int index = 0; index < order.size(); index++) {
            if (id.toString().equals(order.get(index).path("id").asText())) {
                return index;
            }
        }
        throw new AssertionError("expected node is absent from execution order");
    }
}
