package com.lifeos.calendar.authorization;

/** Raised when Identity cannot be safely reached or returns an unusable policy response. */
public class CalendarAuthorizationDependencyUnavailable extends RuntimeException {

    public CalendarAuthorizationDependencyUnavailable() {
        super("Calendar authorization dependency unavailable");
    }

    public CalendarAuthorizationDependencyUnavailable(Throwable cause) {
        super("Calendar authorization dependency unavailable", cause);
    }
}
