package com.lifeos.taskgoal.task.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/** Complete mutable representation for an active Task. */
public record UpdateTaskRequest(
        @NotBlank @Size(max = 255) String title,
        @Min(0) @Max(4) Integer priority,
        Instant dueAt) {

    public UpdateTaskRequest(String title) {
        this(title, null, null);
    }
}
