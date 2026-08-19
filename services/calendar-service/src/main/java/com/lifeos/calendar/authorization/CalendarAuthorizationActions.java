package com.lifeos.calendar.authorization;

/** Stable action strings that the parallel Identity policy slice grants explicitly. */
public final class CalendarAuthorizationActions {

    public static final String EVENT_CREATE = "calendar:event-create";
    public static final String EVENT_LIST = "calendar:event-list";
    public static final String EVENT_READ = "calendar:event-read";
    public static final String EVENT_UPDATE = "calendar:event-update";
    public static final String EVENT_CANCEL = "calendar:event-cancel";
    public static final String TIME_BLOCK_CREATE = "calendar:time-block-create";
    public static final String TIME_BLOCK_LIST = "calendar:time-block-list";
    public static final String TIME_BLOCK_READ = "calendar:time-block-read";
    public static final String TIME_BLOCK_UPDATE = "calendar:time-block-update";
    public static final String TIME_BLOCK_CANCEL = "calendar:time-block-cancel";
    public static final String CONFLICT_READ = "calendar:conflict-read";
    public static final String OPTIMIZE = "calendar:optimize";

    private CalendarAuthorizationActions() {
    }
}
