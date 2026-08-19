package com.lifeos.profile.journal;

/** Missing and cross-owner journal entries share one non-enumerating response. */
public class JournalNotFoundException extends RuntimeException {

    public JournalNotFoundException() {
        super("Journal entry not found");
    }
}
