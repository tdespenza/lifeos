package com.lifeos.calendar.authorization;

/** Raised for a deterministic deny decision without leaking ownership or existence details. */
public class CalendarAuthorizationDenied extends RuntimeException {

    public CalendarAuthorizationDenied() {
        super("Calendar authorization denied");
    }
}
