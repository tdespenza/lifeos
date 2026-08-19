package com.lifeos.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.lifeos.assistant.authorization.AssistantSubject;
import com.lifeos.assistant.config.AssistantTaskGoalToolProperties;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RestClientAssistantTaskGoalClientTest {

    private static final AssistantSubject SUBJECT = new AssistantSubject(
            UUID.randomUUID(), UUID.randomUUID(), "password", "a".repeat(64));

    @Test
    void forwardsWorkloadProofAndIdempotencyWithoutForwardingBearer() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://task-goal.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AssistantTaskGoalToolProperties properties = properties();
        RestClientAssistantTaskGoalClient client = new RestClientAssistantTaskGoalClient(
                builder.build(), properties, new Semaphore(1));
        UUID taskId = UUID.randomUUID();

        server.expect(requestTo("http://task-goal.test/api/v1/internal/assistant/tasks"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-LifeOS-Workload-Identity", "ai-assistant-service"))
                .andExpect(header("X-LifeOS-Workload-Token", "assistant-secret"))
                .andExpect(header("Idempotency-Key", "task-key"))
                .andExpect(jsonPath("$.subjectId").value(SUBJECT.accountId().toString()))
                .andExpect(jsonPath("$.accessTokenProof").value("a".repeat(64)))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\":\"" + taskId + "\",\"title\":\"Write plan\",\"status\":\"ACTIVE\","
                                + "\"version\":0,\"priority\":3}"));

        AssistantTaskGoalClient.TaskCreationResult result = client.createTask(
                SUBJECT, "Write plan", 3, null, "task-key");

        assertThat(result.id()).isEqualTo(taskId);
        assertThat(result.title()).isEqualTo("Write plan");
        server.verify();
    }

    @Test
    void mapsIdentityDenialToAStableToolException() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://task-goal.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClientAssistantTaskGoalClient client = new RestClientAssistantTaskGoalClient(
                builder.build(), properties(), new Semaphore(1));
        server.expect(requestTo("http://task-goal.test/api/v1/internal/assistant/tasks"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.createTask(
                        SUBJECT, "Write plan", null, Instant.now(), "task-key"))
                .isInstanceOf(AssistantTaskToolDeniedException.class);
        server.verify();
    }

    @Test
    void readsOnlyTheBoundedPlanningSnapshotThroughTheWorkloadBoundary() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://task-goal.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClientAssistantTaskGoalClient client = new RestClientAssistantTaskGoalClient(
                builder.build(), properties(), new Semaphore(1));
        UUID taskId = UUID.randomUUID();
        server.expect(requestTo("http://task-goal.test/api/v1/internal/assistant/planning-snapshot"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-LifeOS-Workload-Identity", "ai-assistant-service"))
                .andExpect(header("X-LifeOS-Workload-Token", "assistant-secret"))
                .andExpect(jsonPath("$.maxResults").value(8))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"facts\":[{\"resourceType\":\"TASK\",\"resourceId\":\""
                                + taskId + "\",\"title\":\"Write plan\",\"status\":\"ACTIVE\","
                                + "\"priority\":1,\"dueAt\":null}]}"));

        AssistantTaskGoalClient.PlanningSnapshot snapshot = client.planningSnapshot(SUBJECT, 8);

        assertThat(snapshot.facts()).hasSize(1);
        assertThat(snapshot.facts().getFirst().resourceId()).isEqualTo(taskId);
        server.verify();
    }

    private static AssistantTaskGoalToolProperties properties() {
        AssistantTaskGoalToolProperties properties = new AssistantTaskGoalToolProperties();
        properties.setBaseUrl("http://task-goal.test");
        properties.setWorkloadIdentity("ai-assistant-service");
        properties.setWorkloadToken("assistant-secret");
        return properties;
    }
}
