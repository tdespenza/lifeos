package com.lifeos.media.config;

/** HLS processing adapter modes. No mode silently claims transcoding succeeded. */
public enum MediaProcessingMode {
    EXTERNAL_WORKER_REQUIRED,
    LOCAL_DEVELOPMENT
}
