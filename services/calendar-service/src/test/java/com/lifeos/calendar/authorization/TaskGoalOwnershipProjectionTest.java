package com.lifeos.calendar.authorization;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.lifeos.calendar.domain.CalendarLinkType;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskGoalOwnershipProjectionTest {

    @Test
    void defaultPlanningProjectionFailsClosedInsteadOfReturningNullFacts() {
        TaskGoalOwnershipProjection projection = mock(TaskGoalOwnershipProjection.class,
                org.mockito.Mockito.CALLS_REAL_METHODS);
        CalendarSubject subject = new CalendarSubject(
                UUID.randomUUID(), UUID.randomUUID(), "PASSWORD", "a".repeat(64));
        UUID resourceId = UUID.randomUUID();

        assertThatThrownBy(() -> projection.project(subject, CalendarLinkType.TASK, resourceId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("TaskGoalOwnershipProjection must implement planning facts");
        verify(projection).verify(subject, CalendarLinkType.TASK, resourceId);
    }
}
