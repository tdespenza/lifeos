package com.lifeos.taskgoal.task;

/** Raised when a command would mutate a terminal Task lifecycle state. */
public class TaskLifecycleTransitionException extends RuntimeException {

    public TaskLifecycleTransitionException(String operation) {
        super("Task lifecycle transition is not valid: " + operation);
    }
}
