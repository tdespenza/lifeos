package com.lifeos.taskgoal.task;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifeos.taskgoal.authorization.TaskAccessService;
import com.lifeos.taskgoal.authorization.TaskSubject;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Executable HTTP contract for Task create plus strict lifecycle precondition validation. */
@WebMvcTest(TaskController.class)
class TaskControllerContractTest {

    private static final String ACCESS_TOKEN_PROOF =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TaskService taskService;

    @MockitoBean
    private TaskAccessService accessService;

    private TaskSubject subject;

    @BeforeEach
    void setUp() {
        subject = new TaskSubject(UUID.randomUUID(), UUID.randomUUID(), "password", ACCESS_TOKEN_PROOF);
        when(accessService.authenticate(any())).thenReturn(subject);
    }

    @Test
    void createReturnsVersionedResourceAndLocation() throws Exception {
        UUID taskId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-17T12:00:00Z");
        when(taskService.create(eq(subject), eq("Prepare launch"), eq("task-create-key")))
                .thenReturn(new TaskLifecycleResult(taskId, "Prepare launch", TaskStatus.ACTIVE, 0L, now, now, null, null));

        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", "Bearer test-token")
                        .header("Idempotency-Key", "task-create-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Prepare launch\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/tasks/" + taskId))
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void completeRejectsMissingStrongVersionBeforeServiceWork() throws Exception {
        mockMvc.perform(post("/api/v1/tasks/{taskId}/complete", UUID.randomUUID())
                        .header("Authorization", "Bearer test-token")
                        .header("Idempotency-Key", "task-complete-key"))
                .andExpect(status().is(428))
                .andExpect(jsonPath("$.error").value("If-Match is required for task lifecycle mutations"));
        verifyNoInteractions(taskService);
    }
}
