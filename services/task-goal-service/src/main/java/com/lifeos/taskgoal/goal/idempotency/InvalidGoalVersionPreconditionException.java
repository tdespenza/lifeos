package com.lifeos.taskgoal.goal.idempotency;

/** A lifecycle write supplied a duplicated, weak, wildcard, malformed, or out-of-range ETag. */
public class InvalidGoalVersionPreconditionException extends RuntimeException {
}
