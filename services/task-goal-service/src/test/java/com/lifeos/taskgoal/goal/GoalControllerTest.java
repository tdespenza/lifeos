package com.lifeos.taskgoal.goal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeos.taskgoal.goal.algorithm.CyclicDependencyException;
import com.lifeos.taskgoal.goal.algorithm.DependencyEdge;
import com.lifeos.taskgoal.goal.dto.DependencyOrderRequest;
import java.util.List;
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

    @Test
    void dependencyOrderReturnsOrderedGoals() throws Exception {
        when(goalService.resolveDependencyOrder(anyList(), anyList())).thenReturn(List.of("A", "B"));

        DependencyOrderRequest request = new DependencyOrderRequest(
                List.of("A", "B"), List.of(new DependencyEdge("A", "B")));

        mockMvc.perform(post("/api/v1/goals/dependency-order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order[0]").value("A"))
                .andExpect(jsonPath("$.order[1]").value("B"));
    }

    @Test
    void dependencyOrderReturnsConflictOnCycle() throws Exception {
        when(goalService.resolveDependencyOrder(anyList(), anyList()))
                .thenThrow(new CyclicDependencyException(List.of("A", "B")));

        DependencyOrderRequest request = new DependencyOrderRequest(
                List.of("A", "B"),
                List.of(new DependencyEdge("A", "B"), new DependencyEdge("B", "A")));

        mockMvc.perform(post("/api/v1/goals/dependency-order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }
}
