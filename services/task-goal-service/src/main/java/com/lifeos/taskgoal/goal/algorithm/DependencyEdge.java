package com.lifeos.taskgoal.goal.algorithm;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A directed dependency: {@code before} must be completed before {@code after}.
 */
public record DependencyEdge(
        @NotBlank @Size(max = TopologicalSortService.MAX_NODE_LABEL_LENGTH) String before,
        @NotBlank @Size(max = TopologicalSortService.MAX_NODE_LABEL_LENGTH) String after) {
}
