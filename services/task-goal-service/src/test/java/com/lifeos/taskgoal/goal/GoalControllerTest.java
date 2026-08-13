package com.lifeos.taskgoal.goal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeos.taskgoal.authorization.TaskAccessService;
import com.lifeos.taskgoal.authorization.TaskAuthenticationFailure;
import com.lifeos.taskgoal.authorization.TaskAuthorizationDenied;
import com.lifeos.taskgoal.authorization.TaskAuthorizationDependencyUnavailable;
import com.lifeos.taskgoal.authorization.TaskSubject;
import com.lifeos.taskgoal.goal.algorithm.CyclicDependencyException;
import com.lifeos.taskgoal.goal.algorithm.DependencyEdge;
import com.lifeos.taskgoal.goal.dto.DependencyOrderRequest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(GoalController.class)
class GoalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private GoalService goalService;

    @MockitoBean
    private TaskAccessService accessService;

    private TaskSubject subject;

    @BeforeEach
    void setUp() {
        subject = new TaskSubject(UUID.randomUUID(), UUID.randomUUID(), "password");
        when(accessService.authenticate(any())).thenReturn(subject);
    }

    @Test
    void dependencyOrderReturnsOrderedGoals() throws Exception {
        when(goalService.resolveDependencyOrder(any(), anyList(), anyList())).thenReturn(List.of("A", "B"));

        DependencyOrderRequest request = new DependencyOrderRequest(
                List.of("A", "B"), List.of(new DependencyEdge("A", "B")));

        mockMvc.perform(post("/api/v1/goals/dependency-order")
                        .header("Authorization", "Bearer test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order[0]").value("A"))
                .andExpect(jsonPath("$.order[1]").value("B"));
    }

    @Test
    void dependencyOrderReturnsConflictOnCycle() throws Exception {
        when(goalService.resolveDependencyOrder(any(), anyList(), anyList()))
                .thenThrow(new CyclicDependencyException(List.of("A", "B")));

        DependencyOrderRequest request = new DependencyOrderRequest(
                List.of("A", "B"),
                List.of(new DependencyEdge("A", "B"), new DependencyEdge("B", "A")));

        mockMvc.perform(post("/api/v1/goals/dependency-order")
                        .header("Authorization", "Bearer test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    @Test
    void missingBearerReturnsGenericAuthenticationFailure() throws Exception {
        when(accessService.authenticate(isNull())).thenThrow(new TaskAuthenticationFailure());

        DependencyOrderRequest request = new DependencyOrderRequest(List.of("A"), List.of());

        mockMvc.perform(post("/api/v1/goals/dependency-order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.error").value("Authentication required"));
    }

    @Test
    void deniedGoalReadHasNoResourceDetails() throws Exception {
        UUID goalId = UUID.randomUUID();
        when(goalService.get(any(), any())).thenThrow(new TaskAuthorizationDenied());

        mockMvc.perform(get("/api/v1/goals/{goalId}", goalId).header("Authorization", "Bearer test-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Access denied"))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.title").doesNotExist());
    }

    @Test
    void authorizationDependencyFailureIsGenericAndRetryable() throws Exception {
        when(goalService.get(any(), any())).thenThrow(new TaskAuthorizationDependencyUnavailable());

        mockMvc.perform(get("/api/v1/goals/{goalId}", UUID.randomUUID())
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("Authorization temporarily unavailable"));
    }
}
