package com.lifeos.calendar.authorization;

/** Raised for malformed, expired, revoked, or otherwise unauthenticated bearer credentials. */
public class CalendarAuthenticationFailure extends RuntimeException {

    public CalendarAuthenticationFailure() {
        super("Calendar authentication failed");
    }

    public CalendarAuthenticationFailure(Throwable cause) {
        super("Calendar authentication failed", cause);
    }
}
