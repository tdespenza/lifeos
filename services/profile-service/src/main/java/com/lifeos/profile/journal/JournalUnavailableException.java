package com.lifeos.profile.journal;

/** MongoDB is disabled or unavailable; callers must not fall back to an unencrypted store. */
public class JournalUnavailableException extends RuntimeException {

    public JournalUnavailableException() {
        super("Journal storage is unavailable");
    }

    public JournalUnavailableException(Throwable cause) {
        super("Journal storage is unavailable", cause);
    }
}
