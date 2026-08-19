package com.lifeos.media.domain;

/** Processing state of a stored source asset; HLS cannot be served before HLS_READY. */
public enum MediaAssetStatus {
    AWAITING_UPLOAD,
    STORED_AWAITING_EXTERNAL_PROCESSING,
    HLS_READY,
    PROCESSING_FAILED
}
