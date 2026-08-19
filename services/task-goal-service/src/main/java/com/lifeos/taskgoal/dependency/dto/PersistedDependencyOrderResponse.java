package com.lifeos.taskgoal.dependency.dto;

import java.util.List;

/** Complete deterministic ordering of all caller-owned persisted Task and Goal nodes. */
public record PersistedDependencyOrderResponse(List<PersistedDependencyNodeResponse> order) {
}
