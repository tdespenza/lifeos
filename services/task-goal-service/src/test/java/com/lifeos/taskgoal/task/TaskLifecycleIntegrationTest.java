package com.lifeos.taskgoal.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeos.taskgoal.authorization.TaskAccessService;
import com.lifeos.taskgoal.authorization.TaskAuthorizationActions;
import com.lifeos.taskgoal.authorization.TaskAuthorizationDenied;
import com.lifeos.taskgoal.authorization.TaskSubject;
import com.lifeos.taskgoal.task.idempotency.TaskCommandIdempotencyRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Exercises public Task lifecycle invariants against H2 plus Flyway's production-equivalent schema. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:task-lifecycle;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=task-goal-test-password",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration-h2",
    "identity.workload-token=integration-test-workload-token"
})
@AutoConfigureMockMvc
class TaskLifecycleIntegrationTest {

    private static final String BEARER = "Bearer task-lifecycle-test-token";
    private static final String ACCESS_TOKEN_PROOF =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private TaskCommandIdempotencyRepository idempotencyRepository;

    @MockitoBean
    private TaskAccessService accessService;

    private TaskSubject subject;

    @BeforeEach
    void setUp() {
        idempotencyRepository.deleteAll();
        taskRepository.deleteAll();
        reset(accessService);
        subject = new TaskSubject(UUID.randomUUID(), UUID.randomUUID(), "password", ACCESS_TOKEN_PROOF);
        when(accessService.authenticate(anyString())).thenReturn(subject);
    }

    @Test
    void createListReadAndExactCreateReplayRemainOwnerScoped() throws Exception {
        MvcResult first = mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", BEARER)
                        .header("Idempotency-Key", "task-create-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Prepare launch\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"0\""))
                .andReturn();
        MvcResult retry = mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", BEARER)
                        .header("Idempotency-Key", "task-create-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"title\" : \"Prepare launch\" }"))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode firstBody = objectMapper.readTree(first.getResponse().getContentAsString());
        JsonNode retryBody = objectMapper.readTree(retry.getResponse().getContentAsString());
        UUID taskId = UUID.fromString(firstBody.path("id").asText());
        assertThat(retryBody).isEqualTo(firstBody);
        assertThat(taskRepository.count()).isEqualTo(1L);
        assertThat(idempotencyRepository.count()).isEqualTo(1L);

        taskRepository.saveAndFlush(new Task(
                UUID.randomUUID(), "Other user's task", UUID.randomUUID(), UUID.randomUUID().toString()));
        mockMvc.perform(get("/api/v1/tasks").header("Authorization", BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(taskId.toString()));
        mockMvc.perform(get("/api/v1/tasks/{taskId}", taskId).header("Authorization", BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Prepare launch"));

        verify(accessService, times(2))
                .authorize(eq(subject), eq(TaskAuthorizationActions.CREATE), org.mockito.ArgumentMatchers.any());
        verify(accessService).authorize(eq(subject), eq(TaskAuthorizationActions.LIST), org.mockito.ArgumentMatchers.any());
        verify(accessService).authorize(eq(subject), eq(TaskAuthorizationActions.READ), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void lifecycleRequiresStrongVersionAndRetainsExactMutationReplaySnapshots() throws Exception {
        UUID taskId = create("Original", "task-create-lifecycle-key");

        MvcResult updated = mockMvc.perform(put("/api/v1/tasks/{taskId}", taskId)
                        .header("Authorization", BEARER)
                        .header("Idempotency-Key", "task-update-key")
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"First title\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andReturn();

        mockMvc.perform(put("/api/v1/tasks/{taskId}", taskId)
                        .header("Authorization", BEARER)
                        .header("Idempotency-Key", "task-second-update-key")
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Second title\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""));

        MvcResult updateReplay = mockMvc.perform(put("/api/v1/tasks/{taskId}", taskId)
                        .header("Authorization", BEARER)
                        .header("Idempotency-Key", "task-update-key")
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"First title\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andReturn();
        assertThat(objectMapper.readTree(updateReplay.getResponse().getContentAsString()))
                .isEqualTo(objectMapper.readTree(updated.getResponse().getContentAsString()));

        mockMvc.perform(post("/api/v1/tasks/{taskId}/complete", taskId)
                        .header("Authorization", BEARER)
                        .header("Idempotency-Key", "task-complete-key")
                        .header("If-Match", "\"2\""))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"3\""))
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        mockMvc.perform(post("/api/v1/tasks/{taskId}/cancel", taskId)
                        .header("Authorization", BEARER)
                        .header("Idempotency-Key", "task-cancel-after-complete-key")
                        .header("If-Match", "\"3\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Task lifecycle transition is not valid"));

        mockMvc.perform(put("/api/v1/tasks/{taskId}", taskId)
                        .header("Authorization", BEARER)
                        .header("Idempotency-Key", "task-invalid-precondition-key")
                        .header("If-Match", "W/\"3\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Not applied\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/tasks/{taskId}/cancel", taskId)
                        .header("Authorization", BEARER)
                        .header("Idempotency-Key", "task-missing-precondition-key"))
                .andExpect(status().is(428));
    }

    @Test
    void cancelIsTerminalAndMissingAndCrossUserTasksHaveTheSameNoDisclosureResponse() throws Exception {
        UUID taskId = create("Cancel me", "task-cancel-create-key");
        mockMvc.perform(post("/api/v1/tasks/{taskId}/cancel", taskId)
                        .header("Authorization", BEARER)
                        .header("Idempotency-Key", "task-cancel-key")
                        .header("If-Match", "\"0\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"));

        Task foreign = taskRepository.saveAndFlush(new Task(
                UUID.randomUUID(), "Confidential task", UUID.randomUUID(), UUID.randomUUID().toString()));
        doThrow(new TaskAuthorizationDenied())
                .when(accessService)
                .authorize(eq(subject), eq(TaskAuthorizationActions.READ), org.mockito.ArgumentMatchers.any());
        MvcResult crossUser = mockMvc.perform(get("/api/v1/tasks/{taskId}", foreign.getId())
                        .header("Authorization", BEARER))
                .andExpect(status().isForbidden())
                .andReturn();
        MvcResult missing = mockMvc.perform(get("/api/v1/tasks/{taskId}", UUID.randomUUID())
                        .header("Authorization", BEARER))
                .andExpect(status().isForbidden())
                .andReturn();
        assertThat(missing.getResponse().getContentAsString())
                .isEqualTo(crossUser.getResponse().getContentAsString())
                .doesNotContain(foreign.getTitle())
                .doesNotContain(foreign.getId().toString());
    }

    private UUID create(String title, String key) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", BEARER)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString()).path("id").asText());
    }
}
