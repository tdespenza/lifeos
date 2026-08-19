package com.lifeos.taskgoal.goal.algorithm;

import java.util.List;
import java.util.Objects;

/**
 * Raised when a bounded dependency graph cannot be topologically ordered.
 *
 * <p>The unresolved list contains every node that remains blocked after Kahn's pass. That means
 * it contains at least one cycle participant and can also contain nodes downstream of a cycle.
 * The exception deliberately uses a generic message so user-provided node labels are not copied
 * into application logs through an exception message.
 */
public class CyclicDependencyException extends RuntimeException {

    private final List<String> unresolved;

    public CyclicDependencyException(List<String> unresolved) {
        super("Goal dependencies contain a cycle");
        this.unresolved = List.copyOf(Objects.requireNonNull(unresolved, "unresolved must not be null"));
    }

    public List<String> getUnresolved() {
        return unresolved;
    }
}
