package com.lifeos.calendar.audit;

/** Fails a security-relevant write closed when audit persistence cannot be trusted. */
public class CalendarAuditUnavailableException extends RuntimeException {
    public CalendarAuditUnavailableException(Throwable cause) {
        super(cause);
    }
}
