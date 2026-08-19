package com.lifeos.media.service;

/** TaskGoal could not safely complete the explicit follow-up command. */
public class MediaTaskGoalUnavailableException extends RuntimeException {
    public MediaTaskGoalUnavailableException() { }
    public MediaTaskGoalUnavailableException(Throwable cause) { super(cause); }
}
