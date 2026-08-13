package com.lifeos.taskgoal.authorization;

/** Raised only when the identity service rejects the caller's bearer credential. */
public class TaskAuthenticationFailure extends RuntimeException {

    public TaskAuthenticationFailure() {
        super();
    }

    /**
     * Retains diagnostic causality without deriving this boundary exception's message from a
     * potentially sensitive downstream exception.
     *
     * @param cause original failure
     */
    public TaskAuthenticationFailure(Throwable cause) {
        super(null, cause);
    }
}
