package com.lifeos.calendar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lifeos.calendar.api.CalendarOptimizationRequest;
import com.lifeos.calendar.api.CalendarPlanningCandidateRequest;
import com.lifeos.calendar.authorization.CalendarAccessService;
import com.lifeos.calendar.authorization.CalendarSubject;
import com.lifeos.calendar.authorization.TaskGoalOwnershipProjection;
import com.lifeos.calendar.domain.CalendarLinkType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit coverage for bounded local advice when the TaskGoal projection is intentionally absent. */
class CalendarOptimizationServiceTest {

    private static final String ACCESS_TOKEN_PROOF =
            "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";

    @Test
    void exposesAndMeasuresTheSafeTaskGoalDegradation() {
        CalendarAccessService accessService = mock(CalendarAccessService.class);
        CalendarConflictDetector conflictDetector = mock(CalendarConflictDetector.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CalendarOptimizationService service = new CalendarOptimizationService(
                accessService, conflictDetector, new CalendarSchedulingMetrics(registry));
        CalendarSubject subject = new CalendarSubject(
                UUID.randomUUID(), UUID.randomUUID(), "password", ACCESS_TOKEN_PROOF);
        Instant from = Instant.parse("2026-08-18T09:00:00Z");
        Instant to = Instant.parse("2026-08-18T10:00:00Z");
        when(conflictDetector.detect(subject.tenantId(), subject.accountId(), from, to)).thenReturn(List.of());

        var response = service.suggest(subject, new CalendarOptimizationRequest(from, to, 30, 5));

        assertThat(response.degradedSources()).containsExactly("task-goal");
        assertThat(registry.get("calendar.optimization.degraded").tag("source", "task-goal").counter().count())
                .isEqualTo(1.0d);
        verify(accessService).authorize(
                org.mockito.ArgumentMatchers.eq(subject),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void ranksAuthorizedPlanningCandidatesByPriorityThenDeadline() {
        CalendarAccessService accessService = mock(CalendarAccessService.class);
        CalendarConflictDetector conflictDetector = mock(CalendarConflictDetector.class);
        TaskGoalOwnershipProjection projection = mock(TaskGoalOwnershipProjection.class);
        CalendarOptimizationService service = new CalendarOptimizationService(
                accessService, conflictDetector, new CalendarSchedulingMetrics(new SimpleMeterRegistry()), projection);
        CalendarSubject subject = new CalendarSubject(
                UUID.randomUUID(), UUID.randomUUID(), "password", ACCESS_TOKEN_PROOF);
        Instant from = Instant.parse("2026-08-18T09:00:00Z");
        Instant to = Instant.parse("2026-08-18T12:00:00Z");
        UUID lowPriority = UUID.randomUUID();
        UUID urgent = UUID.randomUUID();
        when(conflictDetector.detect(subject.tenantId(), subject.accountId(), from, to)).thenReturn(List.of());
        when(projection.project(subject, CalendarLinkType.TASK, lowPriority))
                .thenReturn(new TaskGoalOwnershipProjection.TaskGoalPlanningFacts(
                        CalendarLinkType.TASK, lowPriority, 4, Instant.parse("2026-08-19T09:00:00Z")));
        when(projection.project(subject, CalendarLinkType.GOAL, urgent))
                .thenReturn(new TaskGoalOwnershipProjection.TaskGoalPlanningFacts(
                        CalendarLinkType.GOAL, urgent, 0, Instant.parse("2026-08-18T10:00:00Z")));

        var response = service.suggest(subject, new CalendarOptimizationRequest(
                from,
                to,
                30,
                2,
                List.of(
                        new CalendarPlanningCandidateRequest(CalendarLinkType.TASK, lowPriority, 30),
                        new CalendarPlanningCandidateRequest(CalendarLinkType.GOAL, urgent, 30))));

        assertThat(response.degradedSources()).isEmpty();
        assertThat(response.suggestions()).extracting("reason")
                .first().asString().contains("Priority 0 GOAL " + urgent);
        assertThat(response.suggestions()).extracting("reason")
                .last().asString().contains("Priority 4 TASK " + lowPriority);
    }
}
