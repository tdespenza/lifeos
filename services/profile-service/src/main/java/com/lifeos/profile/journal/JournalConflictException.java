package com.lifeos.profile.journal;

/** Idempotency, optimistic-version, or lifecycle conflict without sensitive details. */
public class JournalConflictException extends RuntimeException {

    public JournalConflictException() {
        super("Journal entry is no longer current");
    }
}
