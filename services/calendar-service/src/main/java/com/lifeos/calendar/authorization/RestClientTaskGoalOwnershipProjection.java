package com.lifeos.calendar.authorization;

import com.lifeos.calendar.config.CalendarProperties;
import com.lifeos.calendar.domain.CalendarLinkType;
import com.lifeos.calendar.service.UnsupportedCalendarLinkException;
import java.net.http.HttpClient;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Bounded workload-authenticated adapter for TaskGoal's ownership projection. */
@Component
public class RestClientTaskGoalOwnershipProjection implements TaskGoalOwnershipProjection {

    private static final String PATH = "/api/v1/internal/planning/ownership-proof";
    private static final String PLANNING_PATH = "/api/v1/internal/planning/priority-projection";
    private static final String WORKLOAD_IDENTITY = "X-LifeOS-Workload-Identity";
    private static final String WORKLOAD_TOKEN = "X-LifeOS-Workload-Token";

    private final RestClient restClient;
    private final CalendarProperties.TaskGoalProjection properties;

    @Autowired
    public RestClientTaskGoalOwnershipProjection(
            RestClient.Builder builder, CalendarProperties calendarProperties) {
        this(buildRestClient(builder, calendarProperties.getTaskGoalProjection()),
                calendarProperties.getTaskGoalProjection());
    }

    RestClientTaskGoalOwnershipProjection(
            RestClient restClient, CalendarProperties.TaskGoalProjection properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public void verify(CalendarSubject subject, CalendarLinkType linkType, UUID resourceId) {
        if (linkType == CalendarLinkType.FOCUS) {
            return;
        }
        if (subject == null || resourceId == null || !properties.configured()) {
            throw new UnsupportedCalendarLinkException();
        }
        String resourceType = linkType == CalendarLinkType.TASK ? "TASK" : "GOAL";
        try {
            HttpStatusCode status = restClient.post()
                    .uri(PATH)
                    .header(WORKLOAD_IDENTITY, properties.getWorkloadIdentity())
                    .header(WORKLOAD_TOKEN, properties.getWorkloadToken())
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .body(new OwnershipProjectionRequest(
                            subject.accountId(),
                            subject.sessionId(),
                            subject.authenticationMethod(),
                            subject.accessTokenProof(),
                            resourceType,
                            resourceId.toString()))
                    .exchange((request, response) -> {
                        response.getBody().close();
                        return response.getStatusCode();
                    });
            if (status.value() == 204) {
                return;
            }
            throw new UnsupportedCalendarLinkException();
        } catch (UnsupportedCalendarLinkException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            throw new UnsupportedCalendarLinkException();
        } catch (RuntimeException exception) {
            throw new UnsupportedCalendarLinkException();
        }
    }

    @Override
    public TaskGoalPlanningFacts project(CalendarSubject subject, CalendarLinkType linkType, UUID resourceId) {
        if (linkType == CalendarLinkType.FOCUS || subject == null || resourceId == null || !properties.configured()) {
            throw new UnsupportedCalendarLinkException();
        }
        String resourceType = linkType == CalendarLinkType.TASK ? "TASK" : "GOAL";
        try {
            TaskGoalPlanningResponse response = restClient.post()
                    .uri(PLANNING_PATH)
                    .header(WORKLOAD_IDENTITY, properties.getWorkloadIdentity())
                    .header(WORKLOAD_TOKEN, properties.getWorkloadToken())
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .body(new OwnershipProjectionRequest(
                            subject.accountId(),
                            subject.sessionId(),
                            subject.authenticationMethod(),
                            subject.accessTokenProof(),
                            resourceType,
                            resourceId.toString()))
                    .retrieve()
                    .toEntity(TaskGoalPlanningResponse.class)
                    .getBody();
            if (response == null || !resourceType.equals(response.resourceType()) || !resourceId.equals(response.resourceId())) {
                throw new UnsupportedCalendarLinkException();
            }
            return new TaskGoalPlanningFacts(linkType, resourceId, response.priority(), response.dueAt());
        } catch (UnsupportedCalendarLinkException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new UnsupportedCalendarLinkException();
        }
    }

    private static RestClient buildRestClient(
            RestClient.Builder builder, CalendarProperties.TaskGoalProjection properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getReadTimeout());
        return builder.baseUrl(properties.getBaseUrl()).requestFactory(requestFactory).build();
    }

    record OwnershipProjectionRequest(
            UUID subjectId,
            UUID sessionId,
            String authenticationMethod,
            String accessTokenProof,
            String resourceType,
            String resourceId) {
    }

    record TaskGoalPlanningResponse(String resourceType, UUID resourceId, int priority, java.time.Instant dueAt) {}
}
