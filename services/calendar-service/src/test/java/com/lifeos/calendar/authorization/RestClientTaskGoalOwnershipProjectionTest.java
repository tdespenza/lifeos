package com.lifeos.calendar.authorization;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.lifeos.calendar.config.CalendarProperties;
import com.lifeos.calendar.domain.CalendarLinkType;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RestClientTaskGoalOwnershipProjectionTest {

    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final UUID RESOURCE_ID = UUID.randomUUID();
    private static final String PROOF = "b".repeat(64);

    @Test
    void sendsOnlyTheValidatedSubjectProofAndAcceptsNoContent() {
        CalendarProperties properties = new CalendarProperties();
        properties.getTaskGoalProjection().setBaseUrl("http://task-goal.test");
        properties.getTaskGoalProjection().setWorkloadToken("calendar-secret");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClientTaskGoalOwnershipProjection projection = new RestClientTaskGoalOwnershipProjection(
                builder.build(), properties.getTaskGoalProjection());

        server.expect(once(), requestTo("/api/v1/internal/planning/ownership-proof"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-LifeOS-Workload-Identity", "calendar-service"))
                .andExpect(header("X-LifeOS-Workload-Token", "calendar-secret"))
                .andExpect(jsonPath("$.subjectId").value(ACCOUNT_ID.toString()))
                .andExpect(jsonPath("$.accessTokenProof").value(PROOF))
                .andExpect(jsonPath("$.resourceType").value("TASK"))
                .andExpect(jsonPath("$.resourceId").value(RESOURCE_ID.toString()))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        projection.verify(new CalendarSubject(ACCOUNT_ID, SESSION_ID, "PASSWORD", PROOF), CalendarLinkType.TASK, RESOURCE_ID);

        server.verify();
    }

    @Test
    void mapsAnyNon204ResponseToTheGenericUnsupportedLinkBoundary() {
        CalendarProperties properties = new CalendarProperties();
        properties.getTaskGoalProjection().setBaseUrl("http://task-goal.test");
        properties.getTaskGoalProjection().setWorkloadToken("calendar-secret");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClientTaskGoalOwnershipProjection projection = new RestClientTaskGoalOwnershipProjection(
                builder.build(), properties.getTaskGoalProjection());
        server.expect(requestTo("/api/v1/internal/planning/ownership-proof"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> projection.verify(
                        new CalendarSubject(ACCOUNT_ID, SESSION_ID, "PASSWORD", PROOF),
                        CalendarLinkType.GOAL,
                        RESOURCE_ID))
                .isInstanceOf(com.lifeos.calendar.service.UnsupportedCalendarLinkException.class);
    }

    @Test
    void failsClosedWithoutAProvisionedProjectionToken() {
        CalendarProperties properties = new CalendarProperties();
        properties.getTaskGoalProjection().setWorkloadToken("");
        RestClientTaskGoalOwnershipProjection projection = new RestClientTaskGoalOwnershipProjection(
                RestClient.builder().baseUrl("http://task-goal.test").build(), properties.getTaskGoalProjection());

        assertThatThrownBy(() -> projection.verify(
                        new CalendarSubject(ACCOUNT_ID, SESSION_ID, "PASSWORD", PROOF),
                        CalendarLinkType.TASK,
                        RESOURCE_ID))
                .isInstanceOf(com.lifeos.calendar.service.UnsupportedCalendarLinkException.class);
    }
}
