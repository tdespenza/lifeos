package com.lifeos.taskgoal.goal.dto;

import com.lifeos.taskgoal.goal.algorithm.DependencyEdge;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record DependencyOrderRequest(
        @NotEmpty List<String> goals, List<DependencyEdge> dependencies) {
}
