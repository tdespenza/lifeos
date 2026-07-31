package com.lifeos.taskgoal.goal.algorithm;

/**
 * A directed dependency: {@code before} must be completed before {@code after}.
 */
public record DependencyEdge(String before, String after) {
}
