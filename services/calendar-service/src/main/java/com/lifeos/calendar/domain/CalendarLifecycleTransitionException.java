package com.lifeos.calendar.domain;

/** A requested event or time-block lifecycle command is invalid for its persisted state. */
public class CalendarLifecycleTransitionException extends RuntimeException {

    public CalendarLifecycleTransitionException(String operation) {
        super("Calendar resource cannot transition through " + operation);
    }
}
