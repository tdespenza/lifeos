package com.lifeos.taskgoal.goal.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/** Complete mutable representation for the current active-goal update contract. */
public record UpdateGoalRequest(
        @NotBlank @Size(max = 255) String title,
        @Min(0) @Max(4) Integer priority,
        Instant dueAt) {

    public UpdateGoalRequest(String title) {
        this(title, null, null);
    }
}
