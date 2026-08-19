package com.lifeos.media.domain;

/** Safe, low-cardinality result recorded for a media security-relevant operation. */
public enum MediaAuditOutcome {
    SUCCESS,
    DENIED,
    FAILED,
    REPLAYED
}
