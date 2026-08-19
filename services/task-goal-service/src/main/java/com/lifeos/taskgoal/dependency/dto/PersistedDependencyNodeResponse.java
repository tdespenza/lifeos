package com.lifeos.taskgoal.dependency.dto;

import com.lifeos.taskgoal.dependency.DependencyNodeType;
import com.lifeos.taskgoal.dependency.PersistedDependencyNode;
import java.util.UUID;

/** Public reference to a caller-owned node in a persisted execution order. */
public record PersistedDependencyNodeResponse(DependencyNodeType type, UUID id) {

    public static PersistedDependencyNodeResponse from(PersistedDependencyNode node) {
        return new PersistedDependencyNodeResponse(node.type(), node.id());
    }
}
