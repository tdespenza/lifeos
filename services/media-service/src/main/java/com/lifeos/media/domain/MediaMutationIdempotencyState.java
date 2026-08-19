package com.lifeos.media.domain;

/** Lifecycle of an immutable successful mutation response. */
public enum MediaMutationIdempotencyState {
    PENDING,
    COMPLETED
}
