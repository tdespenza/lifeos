package com.lifeos.calendar.authorization;

import com.lifeos.calendar.domain.CalendarLinkType;
import java.util.UUID;

/** Verifies a linked Task/Goal through TaskGoal's no-disclosure internal projection. */
public interface TaskGoalOwnershipProjection {

    void verify(CalendarSubject subject, CalendarLinkType linkType, UUID resourceId);

    /** Returns priority/deadline facts only after exact Task/Goal owner authorization. */
    default TaskGoalPlanningFacts project(CalendarSubject subject, CalendarLinkType linkType, UUID resourceId) {
        verify(subject, linkType, resourceId);
        throw new IllegalStateException("TaskGoalOwnershipProjection must implement planning facts");
    }

    record TaskGoalPlanningFacts(CalendarLinkType linkType, UUID resourceId, int priority, java.time.Instant dueAt) {}
}
