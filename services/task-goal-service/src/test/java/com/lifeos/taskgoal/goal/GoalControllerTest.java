package com.lifeos.taskgoal.goal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import com.lifeos.taskgoal.goal.algorithm.InvalidDependencyGraphException;
import com.lifeos.taskgoal.goal.dto.DependencyOrderRequest;
import com.lifeos.taskgoal.goal.idempotency.GoalIdempotencyConflictException;
import com.lifeos.taskgoal.goal.idempotency.GoalIdempotencyUnavailableException;
import java.time.Instant;
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

    private static final String ACCESS_TOKEN_PROOF =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

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
        subject = new TaskSubject(UUID.randomUUID(), UUID.randomUUID(), "password", ACCESS_TOKEN_PROOF);
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
    void createForwardsTheValidatedIdempotencyKeyAndReturnsCreatedGoal() throws Exception {
        Goal created = new Goal(UUID.randomUUID(), "Prepare launch", subject.accountId(), subject.tenantId());
        when(goalService.create(eq(subject), eq("Prepare launch"), eq("goal-create-key"))).thenReturn(created);

        mockMvc.perform(post("/api/v1/goals")
                        .header("Authorization", "Bearer test-token")
                        .header("Idempotency-Key", "goal-create-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Prepare launch\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/goals/" + created.getId()))
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.id").value(created.getId().toString()))
                .andExpect(jsonPath("$.title").value("Prepare launch"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void createRejectsMissingDuplicatedOrMalformedIdempotencyKeys() throws Exception {
        String request = "{\"title\":\"Prepare launch\"}";

        mockMvc.perform(post("/api/v1/goals")
                        .header("Authorization", "Bearer test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("A valid Idempotency-Key header is required"));

        mockMvc.perform(post("/api/v1/goals")
                        .header("Authorization", "Bearer test-token")
                        .header("Idempotency-Key", "first-key")
                        .header("Idempotency-Key", "second-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("A valid Idempotency-Key header is required"));

        mockMvc.perform(post("/api/v1/goals")
                        .header("Authorization", "Bearer test-token")
                        .header("Idempotency-Key", "not valid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("A valid Idempotency-Key header is required"));

        mockMvc.perform(post("/api/v1/goals")
                        .header("Authorization", "Bearer test-token")
                        .header("Idempotency-Key", "a".repeat(129))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("A valid Idempotency-Key header is required"));

        verifyNoInteractions(goalService);
    }

    @Test
    void createReturnsControlledConflictForIdempotencyPayloadMismatch() throws Exception {
        when(goalService.create(eq(subject), eq("Changed title"), eq("goal-create-key")))
                .thenThrow(new GoalIdempotencyConflictException());

        mockMvc.perform(post("/api/v1/goals")
                        .header("Authorization", "Bearer test-token")
                        .header("Idempotency-Key", "goal-create-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Changed title\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Idempotency key conflicts with an existing request"))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.title").doesNotExist());
    }

    @Test
    void createReturnsControlledRetryableResponseWhenIdempotencyStorageIsUnavailable() throws Exception {
        when(goalService.create(eq(subject), eq("Prepare launch"), eq("goal-create-key")))
                .thenThrow(new GoalIdempotencyUnavailableException());

        mockMvc.perform(post("/api/v1/goals")
                        .header("Authorization", "Bearer test-token")
                        .header("Idempotency-Key", "goal-create-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Prepare launch\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.error").value("Idempotency request is temporarily unavailable"));
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
    void dependencyOrderReturnsControlledBadRequestForDefensivelyRejectedGraph() throws Exception {
        when(goalService.resolveDependencyOrder(any(), anyList(), anyList()))
                .thenThrow(new InvalidDependencyGraphException());

        DependencyOrderRequest request = new DependencyOrderRequest(
                List.of("A", "B"), List.of(new DependencyEdge("A", "B")));

        mockMvc.perform(post("/api/v1/goals/dependency-order")
                        .header("Authorization", "Bearer test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Dependency graph input is invalid"));
    }

    @Test
    void dependencyOrderRejectsMalformedNodesBeforeAuthenticationOrServiceWork() throws Exception {
        mockMvc.perform(post("/api/v1/goals/dependency-order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goals\":[\"\"],\"dependencies\":[]}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(accessService, goalService);
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
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.error").value("Authorization temporarily unavailable"));
    }

    @Test
    void updateRequiresStrongVersionAndReturnsTheMutationSnapshotWithNewEtag() throws Exception {
        UUID goalId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-17T12:00:00Z");
        GoalLifecycleResult result = new GoalLifecycleResult(
                goalId, "Renamed", GoalStatus.ACTIVE, 1L, now, now, null, null);
        when(goalService.update(eq(subject), eq(goalId), eq(0L), eq("Renamed"), eq("goal-update-key")))
                .thenReturn(result);

        mockMvc.perform(put("/api/v1/goals/{goalId}", goalId)
                        .header("Authorization", "Bearer test-token")
                        .header("Idempotency-Key", "goal-update-key")
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Renamed\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.id").value(goalId.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void lifecycleCommandsRejectMissingMalformedAndDuplicatedVersionPreconditionsBeforeServiceWork() throws Exception {
        UUID goalId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/goals/{goalId}/complete", goalId)
                        .header("Authorization", "Bearer test-token")
                        .header("Idempotency-Key", "complete-key"))
                .andExpect(status().is(428))
                .andExpect(jsonPath("$.error").value("If-Match is required for goal lifecycle mutations"));

        mockMvc.perform(post("/api/v1/goals/{goalId}/complete", goalId)
                        .header("Authorization", "Bearer test-token")
                        .header("Idempotency-Key", "complete-key")
                        .header("If-Match", "W/\"0\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("A valid strong If-Match goal version is required"));

        mockMvc.perform(post("/api/v1/goals/{goalId}/complete", goalId)
                        .header("Authorization", "Bearer test-token")
                        .header("Idempotency-Key", "complete-key")
                        .header("If-Match", "\"0\"")
                        .header("If-Match", "\"1\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("A valid strong If-Match goal version is required"));

        verifyNoInteractions(goalService);
    }

    @Test
    void lifecycleCommandsMapStaleVersionAndInvalidTransitionToControlledResponses() throws Exception {
        UUID goalId = UUID.randomUUID();
        when(goalService.complete(eq(subject), eq(goalId), eq(0L), eq("complete-key")))
                .thenThrow(new GoalVersionConflictException());

        mockMvc.perform(post("/api/v1/goals/{goalId}/complete", goalId)
                        .header("Authorization", "Bearer test-token")
                        .header("Idempotency-Key", "complete-key")
                        .header("If-Match", "\"0\""))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.error").value("Goal representation is no longer current"));

        when(goalService.archive(eq(subject), eq(goalId), eq(1L), eq("archive-key")))
                .thenThrow(new GoalLifecycleTransitionException("archive"));
        mockMvc.perform(post("/api/v1/goals/{goalId}/archive", goalId)
                        .header("Authorization", "Bearer test-token")
                        .header("Idempotency-Key", "archive-key")
                        .header("If-Match", "\"1\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Goal lifecycle transition is not valid"));
    }

    @Test
    void lifecycleCommandsForwardValidatedIdempotencyAndVersionToService() throws Exception {
        UUID goalId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-17T12:00:00Z");
        GoalLifecycleResult result = new GoalLifecycleResult(
                goalId, "Archive me", GoalStatus.ARCHIVED, 1L, now, now, null, now);
        when(goalService.archive(eq(subject), eq(goalId), eq(0L), eq("archive-key"))).thenReturn(result);

        mockMvc.perform(post("/api/v1/goals/{goalId}/archive", goalId)
                        .header("Authorization", "Bearer test-token")
                        .header("Idempotency-Key", "archive-key")
                        .header("If-Match", "\"0\""))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
    }
}
