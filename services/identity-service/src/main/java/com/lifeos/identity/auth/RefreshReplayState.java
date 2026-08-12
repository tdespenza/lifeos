package com.lifeos.identity.auth;

/** State of a bounded idempotency record for one refresh rotation. */
public enum RefreshReplayState {
    PENDING,
    COMMITTED
}
