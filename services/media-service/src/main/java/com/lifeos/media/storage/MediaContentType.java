package com.lifeos.media.storage;

import java.util.Locale;

/** Narrow source-media allowlist; filename extensions never authorize binary content. */
public enum MediaContentType {
    MP4("video/mp4"),
    WEBM("video/webm");

    private final String mediaType;

    MediaContentType(String mediaType) {
        this.mediaType = mediaType;
    }

    public String mediaType() {
        return mediaType;
    }

    public static MediaContentType requireAllowed(String value) {
        if (value == null) {
            throw new UnsupportedMediaContentException();
        }
        String normalized = value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        for (MediaContentType candidate : values()) {
            if (candidate.mediaType.equals(normalized)) {
                return candidate;
            }
        }
        throw new UnsupportedMediaContentException();
    }
}
