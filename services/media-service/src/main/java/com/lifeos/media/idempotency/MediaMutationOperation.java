package com.lifeos.media.idempotency;

/** Mutations whose successful responses are retained for safe network retries. */
public enum MediaMutationOperation {
    ASSET_CREATE,
    ASSET_UPLOAD,
    SESSION_CREATE,
    SESSION_UPDATE,
    SESSION_CANCEL,
    SESSION_POST_PROCESS,
    SESSION_FOLLOW_UP_TASK,
    SESSION_SUMMARY_ANCHOR
}
