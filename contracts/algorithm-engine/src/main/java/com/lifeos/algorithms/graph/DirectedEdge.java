package com.lifeos.algorithms.graph;

import java.util.Objects;

/** Immutable directed graph edge where {@code before} must precede {@code after}. */
public record DirectedEdge<T>(T before, T after) {

    public DirectedEdge {
        Objects.requireNonNull(before, "before must not be null");
        Objects.requireNonNull(after, "after must not be null");
    }
}
