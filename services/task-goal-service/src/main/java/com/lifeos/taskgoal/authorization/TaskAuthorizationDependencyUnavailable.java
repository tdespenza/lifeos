package com.lifeos.taskgoal.authorization;

/** Raised when the identity authority cannot be reached or returns an unusable response. */
public class TaskAuthorizationDependencyUnavailable extends RuntimeException {

    public TaskAuthorizationDependencyUnavailable() {
        super();
    }
}
