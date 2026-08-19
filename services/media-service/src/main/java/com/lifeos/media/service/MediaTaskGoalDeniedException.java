package com.lifeos.media.service;

/** TaskGoal rejected the already-authenticated subject proof. */
public class MediaTaskGoalDeniedException extends RuntimeException {
    public MediaTaskGoalDeniedException() { }
    public MediaTaskGoalDeniedException(Throwable cause) { super(cause); }
}
