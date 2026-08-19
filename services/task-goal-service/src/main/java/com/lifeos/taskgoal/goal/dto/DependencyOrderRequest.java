package com.lifeos.taskgoal.goal.dto;

import com.lifeos.taskgoal.goal.algorithm.DependencyEdge;
import com.lifeos.taskgoal.goal.algorithm.TopologicalSortService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public record DependencyOrderRequest(
        @NotEmpty @Size(max = TopologicalSortService.MAX_NODES)
                List<@NotBlank @Size(max = TopologicalSortService.MAX_NODE_LABEL_LENGTH) String> goals,
        @Size(max = TopologicalSortService.MAX_EDGES) List<@Valid DependencyEdge> dependencies) {
}
