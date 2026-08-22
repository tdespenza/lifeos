package com.lifeos.algorithms.ranking;

import java.time.Instant;
import java.util.Objects;

/** An immutable ranking candidate with a higher score preferred and an optional due-time tie break. */
public record PrioritizedItem<T>(T value, int priorityScore, Instant dueAt) {

    public PrioritizedItem {
        Objects.requireNonNull(value, "value must not be null");
    }
}
