package com.lifeos.taskgoal.task.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/** Public create representation; ownership is always derived from the authenticated subject. */
public record CreateTaskRequest(
        @NotBlank @Size(max = 255) String title,
        @Min(0) @Max(4) Integer priority,
        Instant dueAt) {

    public CreateTaskRequest(String title) {
        this(title, null, null);
    }
}
