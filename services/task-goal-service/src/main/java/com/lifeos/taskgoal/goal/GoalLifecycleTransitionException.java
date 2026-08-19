package com.lifeos.taskgoal.goal;

/** A requested lifecycle command is not valid from the goal's persisted state. */
public class GoalLifecycleTransitionException extends RuntimeException {

    public GoalLifecycleTransitionException(String operation) {
        super("Goal cannot transition through " + operation);
    }
}
