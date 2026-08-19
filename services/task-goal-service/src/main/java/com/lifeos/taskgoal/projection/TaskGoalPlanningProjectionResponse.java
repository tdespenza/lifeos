package com.lifeos.taskgoal.projection;

import java.time.Instant;
import java.util.UUID;

/** Non-sensitive planning facts returned only after exact owner authorization. */
public record TaskGoalPlanningProjectionResponse(
        String resourceType, UUID resourceId, int priority, Instant dueAt) {}
