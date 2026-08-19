package com.lifeos.calendar.config;

import java.io.IOException;

/** Raised when a declared or chunked direct-service request exceeds Calendar's body bound. */
public class CalendarPayloadTooLargeException extends IOException {

    public CalendarPayloadTooLargeException() {
        super("Calendar request body exceeds the configured limit");
    }
}
