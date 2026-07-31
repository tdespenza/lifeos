package com.lifeos.taskgoal.goal.dto;

import com.lifeos.taskgoal.goal.Goal;
import java.time.Instant;
import java.util.UUID;

public record GoalResponse(UUID id, String title, Instant createdAt) {

    public static GoalResponse from(Goal goal) {
        return new GoalResponse(goal.getId(), goal.getTitle(), goal.getCreatedAt());
    }
}
