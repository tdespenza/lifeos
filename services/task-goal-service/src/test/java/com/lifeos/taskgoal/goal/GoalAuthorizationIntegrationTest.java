package com.lifeos.taskgoal.goal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import com.lifeos.taskgoal.authorization.GoalAuthorizationActions;
import com.lifeos.taskgoal.authorization.TaskAccessService;
import com.lifeos.taskgoal.authorization.TaskAuthorizationDenied;
import com.lifeos.taskgoal.authorization.TaskAuthorizationDependencyUnavailable;
import com.lifeos.taskgoal.authorization.TaskSubject;
import com.lifeos.taskgoal.goal.idempotency.GoalCreationIdempotencyRepository;
import com.lifeos.taskgoal.goal.idempotency.GoalMutationIdempotencyRepository;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:goal-authorization;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=task-goal-test-password",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    // H2 has no CREATE INDEX CONCURRENTLY; use the test-only equivalent of the production V3.
    "spring.flyway.locations=classpath:db/migration-h2",
    "identity.workload-token=integration-test-workload-token"
})
@AutoConfigureMockMvc
class GoalAuthorizationIntegrationTest {

    private static final String BEARER = "Bearer integration-test-token";
    private static final String ACCESS_TOKEN_PROOF =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GoalRepository repository;

    @Autowired
    private GoalCreationIdempotencyRepository idempotencyRepository;

    @Autowired
    private GoalMutationIdempotencyRepository mutationIdempotencyRepository;

    @Autowired
    private GoalService goalService;

    @MockitoBean
    private TaskAccessService accessService;

    private TaskSubject subject;

    @BeforeEach
    void setUp() {
        mutationIdempotencyRepository.deleteAll();
        idempotencyRepository.deleteAll();
        repository.deleteAll();
        reset(accessService);
        subject = new TaskSubject(UUID.randomUUID(), UUID.randomUUID(), "password", ACCESS_TOKEN_PROOF);
        when(accessService.authenticate(anyString())).thenReturn(subject);
    }

    @Test
    void authenticatedCallerCanCreateListAndReadOnlyItsOwnGoals() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/goals")
                        .header("Authorization", BEARER)
                        .header("Idempotency-Key", "create-list-read-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Prepare launch\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Prepare launch"))
                .andReturn();
        JsonNode createdBody = objectMapper.readTree(created.getResponse().getContentAsString());
        UUID ownedGoalId = UUID.fromString(createdBody.path("id").asText());

        Goal persisted = repository.findById(ownedGoalId).orElseThrow();
        assertThat(persisted.getOwnerAccountId()).isEqualTo(subject.accountId());
        assertThat(persisted.getTenantId()).isEqualTo(subject.tenantId());
        repository.save(new Goal(
                UUID.randomUUID(), "Another account's goal", UUID.randomUUID(), UUID.randomUUID().toString()));

        mockMvc.perform(get("/api/v1/goals").header("Authorization", BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(ownedGoalId.toString()));
        mockMvc.perform(get("/api/v1/goals/{goalId}", ownedGoalId).header("Authorization", BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ownedGoalId.toString()));

        verify(accessService).authorize(eq(subject), eq(GoalAuthorizationActions.CREATE), any());
        verify(accessService).authorize(eq(subject), eq(GoalAuthorizationActions.LIST), any());
        verify(accessService).authorize(eq(subject), eq(GoalAuthorizationActions.READ), any());
    }

    @Test
    void missingAndCrossUserGoalsHaveIdenticalGenericDenyResponses() throws Exception {
        Goal crossUserGoal = repository.save(new Goal(
                UUID.randomUUID(), "Confidential goal", UUID.randomUUID(), UUID.randomUUID().toString()));
        doThrow(new TaskAuthorizationDenied())
                .when(accessService)
                .authorize(eq(subject), eq(GoalAuthorizationActions.READ), any());

        MvcResult crossUser = mockMvc.perform(get("/api/v1/goals/{goalId}", crossUserGoal.getId())
                        .header("Authorization", BEARER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Access denied"))
                .andReturn();
        MvcResult missing = mockMvc.perform(get("/api/v1/goals/{goalId}", UUID.randomUUID())
                        .header("Authorization", BEARER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Access denied"))
                .andReturn();

        assertThat(crossUser.getResponse().getContentAsString())
                .isEqualTo(missing.getResponse().getContentAsString())
                .doesNotContain(crossUserGoal.getId().toString())
                .doesNotContain(crossUserGoal.getTitle());
        verify(accessService, org.mockito.Mockito.times(2))
                .authorize(eq(subject), eq(GoalAuthorizationActions.READ), any());
    }

    @Test
    void matchingCreateRetryReturnsTheOriginalGoalWithoutAnotherInsert() throws Exception {
        String idempotencyKey = "matching-retry-key";
        MvcResult first = mockMvc.perform(post("/api/v1/goals")
                        .header("Authorization", BEARER)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Prepare launch\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        MvcResult retry = mockMvc.perform(post("/api/v1/goals")
                        .header("Authorization", BEARER)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"title\" : \"Prepare launch\" }"))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode firstBody = objectMapper.readTree(first.getResponse().getContentAsString());
        JsonNode retryBody = objectMapper.readTree(retry.getResponse().getContentAsString());
        assertThat(retryBody).isEqualTo(firstBody);
        assertThat(retry.getResponse().getHeader("Location"))
                .isEqualTo(first.getResponse().getHeader("Location"));
        assertThat(repository.count()).isEqualTo(1);
        assertThat(idempotencyRepository.count()).isEqualTo(1);
    }

    @Test
    void matchingCreateRetryAfterALifecycleRenameReturnsTheSameCurrentResource() throws Exception {
        String idempotencyKey = "create-retry-after-rename-key";
        MvcResult first = mockMvc.perform(post("/api/v1/goals")
                        .header("Authorization", BEARER)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Original title\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        UUID goalId = UUID.fromString(objectMapper.readTree(first.getResponse().getContentAsString())
                .path("id")
                .asText());

        mockMvc.perform(put("/api/v1/goals/{goalId}", goalId)
                        .header("Authorization", BEARER)
                        .header("Idempotency-Key", "rename-before-create-retry-key")
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Renamed title\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""));

        mockMvc.perform(post("/api/v1/goals")
                        .header("Authorization", BEARER)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Original title\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/goals/" + goalId))
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.id").value(goalId.toString()))
                .andExpect(jsonPath("$.title").value("Renamed title"))
                .andExpect(jsonPath("$.version").value(1));

        assertThat(repository.count()).isEqualTo(1L);
        assertThat(idempotencyRepository.count()).isEqualTo(1L);
        assertThat(mutationIdempotencyRepository.count()).isEqualTo(1L);
    }

    @Test
    void reusedKeyWithDifferentPayloadReturnsControlledConflictWithoutLeakingOriginalGoal() throws Exception {
        String idempotencyKey = "payload-conflict-key";
        mockMvc.perform(post("/api/v1/goals")
                        .header("Authorization", BEARER)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Private first goal\"}"))
                .andExpect(status().isCreated());

        MvcResult conflict = mockMvc.perform(post("/api/v1/goals")
                        .header("Authorization", BEARER)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Different request\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Idempotency key conflicts with an existing request"))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.title").doesNotExist())
                .andReturn();

        assertThat(conflict.getResponse().getContentAsString()).doesNotContain("Private first goal");
        assertThat(repository.count()).isEqualTo(1);
        assertThat(idempotencyRepository.count()).isEqualTo(1);
    }

    @Test
    void sameKeyIsScopedToTheValidatedAccountAndTenant() {
        Goal first = goalService.create(subject, "First account goal", "shared-client-key");
        TaskSubject otherSubject = new TaskSubject(
                UUID.randomUUID(), UUID.randomUUID(), "password", ACCESS_TOKEN_PROOF);
        Goal second = goalService.create(otherSubject, "Other account goal", "shared-client-key");

        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(repository.count()).isEqualTo(2);
        assertThat(idempotencyRepository.count()).isEqualTo(2);
    }

    @Test
    void concurrentMatchingSubmissionsConvergeOnOneGoal() throws Exception {
        String idempotencyKey = "concurrent-create-key";
        CyclicBarrier startBarrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<MvcResult> submission = () -> {
                startBarrier.await(5, TimeUnit.SECONDS);
                return mockMvc.perform(post("/api/v1/goals")
                                .header("Authorization", BEARER)
                                .header("Idempotency-Key", idempotencyKey)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"title\":\"Only once\"}"))
                        .andReturn();
            };

            Future<MvcResult> first = executor.submit(submission);
            Future<MvcResult> second = executor.submit(submission);
            MvcResult firstResult = first.get(15, TimeUnit.SECONDS);
            MvcResult secondResult = second.get(15, TimeUnit.SECONDS);

            assertThat(firstResult.getResponse().getStatus()).isEqualTo(201);
            assertThat(secondResult.getResponse().getStatus()).isEqualTo(201);
            JsonNode firstBody = objectMapper.readTree(firstResult.getResponse().getContentAsString());
            JsonNode secondBody = objectMapper.readTree(secondResult.getResponse().getContentAsString());
            assertThat(secondBody.path("id").asText()).isEqualTo(firstBody.path("id").asText());
            assertThat(repository.count()).isEqualTo(1);
            assertThat(idempotencyRepository.count()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void policyDenyAndDependencyFailureUseSafe403And503Responses() throws Exception {
        Goal ownedGoal = repository.save(new Goal(
                UUID.randomUUID(), "Owned goal", subject.accountId(), subject.tenantId()));
        doThrow(new TaskAuthorizationDenied())
                .when(accessService)
                .authorize(eq(subject), eq(GoalAuthorizationActions.READ), any());

        mockMvc.perform(get("/api/v1/goals/{goalId}", ownedGoal.getId()).header("Authorization", BEARER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Access denied"));

        reset(accessService);
        when(accessService.authenticate(anyString())).thenReturn(subject);
        doThrow(new TaskAuthorizationDependencyUnavailable())
                .when(accessService)
                .authorize(eq(subject), eq(GoalAuthorizationActions.LIST), any());

        mockMvc.perform(get("/api/v1/goals").header("Authorization", BEARER))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.error").value("Authorization temporarily unavailable"));
    }

    @Test
    void lifecycleMutationsAreVersionedIdempotentAndEnforceExplicitTransitions() throws Exception {
        UUID goalId = createGoal("lifecycle-create-key", "Original");

        MvcResult updated = mockMvc.perform(put("/api/v1/goals/{goalId}", goalId)
                        .header("Authorization", BEARER)
                        .header("Idempotency-Key", "rename-key")
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Renamed\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.title").value("Renamed"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(1))
                .andReturn();

        MvcResult updateReplay = mockMvc.perform(put("/api/v1/goals/{goalId}", goalId)
                        .header("Authorization", BEARER)
                        .header("Idempotency-Key", "rename-key")
                        // The original precondition is deliberately stale after the first commit;
                        // matching replay nevertheless returns the immutable first response.
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Renamed\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andReturn();
        assertThat(objectMapper.readTree(updateReplay.getResponse().getContentAsString()))
                .isEqualTo(objectMapper.readTree(updated.getResponse().getContentAsString()));

        mockMvc.perform(put("/api/v1/goals/{goalId}", goalId)
                        .header("Authorization", BEARER)
                        .header("Idempotency-Key", "stale-rename-key")
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Should not win\"}"))
                .andExpect(status().isPreconditionFailed());

        mockMvc.perform(post("/api/v1/goals/{goalId}/complete", goalId)
                        .header("Authorization", BEARER)
                        .header("Idempotency-Key", "complete-key")
                        .header("If-Match", "\"1\""))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completedAt").isNotEmpty());

        mockMvc.perform(put("/api/v1/goals/{goalId}", goalId)
                        .header("Authorization", BEARER)
                        .header("Idempotency-Key", "completed-rename-key")
                        .header("If-Match", "\"2\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Cannot rename completed\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Goal lifecycle transition is not valid"));

        mockMvc.perform(post("/api/v1/goals/{goalId}/archive", goalId)
                        .header("Authorization", BEARER)
                        .header("Idempotency-Key", "archive-key")
                        .header("If-Match", "\"2\""))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"3\""))
                .andExpect(jsonPath("$.status").value("ARCHIVED"))
                .andExpect(jsonPath("$.archivedAt").isNotEmpty());

        Goal persisted = repository.findById(goalId).orElseThrow();
        assertThat(persisted.getTitle()).isEqualTo("Renamed");
        assertThat(persisted.getStatus()).isEqualTo(GoalStatus.ARCHIVED);
        assertThat(persisted.getVersion()).isEqualTo(3L);
        assertThat(mutationIdempotencyRepository.count()).isEqualTo(5L);
        verify(accessService, times(4)).authorize(eq(subject), eq(GoalAuthorizationActions.UPDATE), any());
        verify(accessService).authorize(eq(subject), eq(GoalAuthorizationActions.COMPLETE), any());
        verify(accessService).authorize(eq(subject), eq(GoalAuthorizationActions.ARCHIVE), any());
    }

    @Test
    void matchingLifecycleReplayReturnsItsImmutableSnapshotAfterALaterMutation() throws Exception {
        UUID goalId = createGoal("snapshot-create-key", "Original");

        MvcResult firstUpdate = mockMvc.perform(put("/api/v1/goals/{goalId}", goalId)
                        .header("Authorization", BEARER)
                        .header("Idempotency-Key", "first-update-key")
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"First title\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andReturn();

        mockMvc.perform(put("/api/v1/goals/{goalId}", goalId)
                        .header("Authorization", BEARER)
                        .header("Idempotency-Key", "second-update-key")
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Second title\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""));

        MvcResult replay = mockMvc.perform(put("/api/v1/goals/{goalId}", goalId)
                        .header("Authorization", BEARER)
                        .header("Idempotency-Key", "first-update-key")
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"First title\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andReturn();

        assertThat(objectMapper.readTree(replay.getResponse().getContentAsString()))
                .isEqualTo(objectMapper.readTree(firstUpdate.getResponse().getContentAsString()));
        Goal persisted = repository.findById(goalId).orElseThrow();
        assertThat(persisted.getTitle()).isEqualTo("Second title");
        assertThat(persisted.getVersion()).isEqualTo(2L);
        assertThat(mutationIdempotencyRepository.count()).isEqualTo(2L);
    }

    @Test
    void lifecycleKeyReuseWithADifferentDecodedPayloadReturnsAConflictWithoutAnotherWrite() throws Exception {
        UUID goalId = createGoal("mutation-conflict-create-key", "Original");

        mockMvc.perform(put("/api/v1/goals/{goalId}", goalId)
                        .header("Authorization", BEARER)
                        .header("Idempotency-Key", "shared-update-key")
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"First title\"}"))
                .andExpect(status().isOk());

        MvcResult conflict = mockMvc.perform(put("/api/v1/goals/{goalId}", goalId)
                        .header("Authorization", BEARER)
                        .header("Idempotency-Key", "shared-update-key")
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Different title\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Idempotency key conflicts with an existing request"))
                .andReturn();

        assertThat(conflict.getResponse().getContentAsString()).doesNotContain("First title");
        Goal persisted = repository.findById(goalId).orElseThrow();
        assertThat(persisted.getTitle()).isEqualTo("First title");
        assertThat(persisted.getVersion()).isEqualTo(1L);
        assertThat(mutationIdempotencyRepository.count()).isEqualTo(1L);
    }

    @Test
    void activeGoalCanArchiveDirectlyAndThenRejectsFurtherLifecycleCommands() throws Exception {
        UUID goalId = createGoal("direct-archive-create-key", "Archive directly");

        mockMvc.perform(post("/api/v1/goals/{goalId}/archive", goalId)
                        .header("Authorization", BEARER)
                        .header("Idempotency-Key", "direct-archive-key")
                        .header("If-Match", "\"0\""))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.status").value("ARCHIVED"));

        mockMvc.perform(post("/api/v1/goals/{goalId}/complete", goalId)
                        .header("Authorization", BEARER)
                        .header("Idempotency-Key", "archived-complete-key")
                        .header("If-Match", "\"1\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Goal lifecycle transition is not valid"));

        Goal persisted = repository.findById(goalId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(GoalStatus.ARCHIVED);
        assertThat(persisted.getCompletedAt()).isNull();
        assertThat(persisted.getArchivedAt()).isNotNull();
        assertThat(persisted.getVersion()).isEqualTo(1L);
    }

    @Test
    void missingAndCrossUserLifecycleCommandsHaveTheSameGenericDenyResponse() throws Exception {
        Goal crossUserGoal = repository.save(new Goal(
                UUID.randomUUID(), "Private goal", UUID.randomUUID(), UUID.randomUUID().toString()));
        doThrow(new TaskAuthorizationDenied())
                .when(accessService)
                .authorize(eq(subject), eq(GoalAuthorizationActions.ARCHIVE), any());

        MvcResult crossUser = mockMvc.perform(post("/api/v1/goals/{goalId}/archive", crossUserGoal.getId())
                        .header("Authorization", BEARER)
                        .header("Idempotency-Key", "cross-user-archive-key")
                        .header("If-Match", "\"0\""))
                .andExpect(status().isForbidden())
                .andReturn();
        MvcResult missing = mockMvc.perform(post("/api/v1/goals/{goalId}/archive", UUID.randomUUID())
                        .header("Authorization", BEARER)
                        .header("Idempotency-Key", "missing-archive-key")
                        .header("If-Match", "\"0\""))
                .andExpect(status().isForbidden())
                .andReturn();

        assertThat(missing.getResponse().getContentAsString())
                .isEqualTo(crossUser.getResponse().getContentAsString())
                .doesNotContain(crossUserGoal.getId().toString())
                .doesNotContain(crossUserGoal.getTitle());
        assertThat(mutationIdempotencyRepository.count()).isZero();
    }

    private UUID createGoal(String idempotencyKey, String title) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/goals")
                        .header("Authorization", BEARER)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString()).path("id").asText());
    }
}
