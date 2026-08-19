package com.lifeos.taskgoal.goal;

/** The supplied representation version is no longer current for a lifecycle mutation. */
public class GoalVersionConflictException extends RuntimeException {

    public GoalVersionConflictException() {
        super("Goal representation is no longer current");
    }
}
