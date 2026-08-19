package com.lifeos.taskgoal.goal.algorithm;

/** Raised for malformed or resource-unbounded dependency-order input. */
public class InvalidDependencyGraphException extends RuntimeException {

    public InvalidDependencyGraphException() {
        super("Dependency graph input is invalid");
    }
}
