package com.lifeos.calendar.service;

import java.util.List;

/** Raised instead of implicitly moving or overwriting an overlapping time block. */
public class CalendarConflictException extends RuntimeException {

    private final List<CalendarConflict> conflicts;

    public CalendarConflictException(List<CalendarConflict> conflicts) {
        this.conflicts = List.copyOf(conflicts);
    }

    public List<CalendarConflict> getConflicts() {
        return conflicts;
    }
}
