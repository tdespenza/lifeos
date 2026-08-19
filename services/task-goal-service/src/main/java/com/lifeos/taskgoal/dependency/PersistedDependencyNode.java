package com.lifeos.taskgoal.dependency;

import java.util.Objects;
import java.util.UUID;

/** Stable graph vertex identity; type prevents a Task UUID from colliding with a Goal UUID. */
public record PersistedDependencyNode(DependencyNodeType type, UUID id) {

    public PersistedDependencyNode {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(id, "id must not be null");
    }
}
