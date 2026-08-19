package com.lifeos.calendar.service;

/** The caller must narrow an exceptionally dense conflict range rather than trigger an unbounded read. */
public class CalendarConflictResultTooLargeException extends RuntimeException {
}
