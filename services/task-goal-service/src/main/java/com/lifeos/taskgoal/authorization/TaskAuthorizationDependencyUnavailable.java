package com.lifeos.taskgoal.authorization;

/** Raised when the identity authority cannot be reached or returns an unusable response. */
public class TaskAuthorizationDependencyUnavailable extends RuntimeException {

    public TaskAuthorizationDependencyUnavailable() {
        super();
    }

    /**
     * Retains diagnostic causality without deriving this boundary exception's message from a
     * potentially sensitive downstream exception.
     *
     * @param cause original failure
     */
    public TaskAuthorizationDependencyUnavailable(Throwable cause) {
        super(null, cause);
    }
}
