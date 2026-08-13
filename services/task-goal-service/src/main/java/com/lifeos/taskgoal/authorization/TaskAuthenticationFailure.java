package com.lifeos.taskgoal.authorization;

/** Raised only when the identity service rejects the caller's bearer credential. */
public class TaskAuthenticationFailure extends RuntimeException {

    public TaskAuthenticationFailure() {
        super();
    }
}
