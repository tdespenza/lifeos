package com.lifeos.taskgoal.goal;

/** Explicit lifecycle states for a persisted goal. */
public enum GoalStatus {
    /** A goal that may be renamed, completed, or archived. */
    ACTIVE,

    /** A goal whose successful outcome was recorded and may only be archived. */
    COMPLETED,

    /** A terminal, read-only goal retained for history. */
    ARCHIVED
}
