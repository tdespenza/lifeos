package com.lifeos.taskgoal.goal.algorithm;

import java.util.List;

public class CyclicDependencyException extends RuntimeException {

    private final List<String> unresolved;

    public CyclicDependencyException(List<String> unresolved) {
        super("Goal dependencies contain a cycle involving: " + unresolved);
        this.unresolved = unresolved;
    }

    public List<String> getUnresolved() {
        return unresolved;
    }
}
