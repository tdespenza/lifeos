package com.lifeos.media.storage;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/** One private HLS object opened only after owner authorization and storage-bound validation. */
public record MediaReadObject(InputStream inputStream, long contentLength, String contentType) implements AutoCloseable {

    public MediaReadObject {
        Objects.requireNonNull(inputStream, "inputStream must not be null");
        if (contentLength < 1) {
            throw new IllegalArgumentException("contentLength must be positive");
        }
        if (contentType == null || contentType.isBlank() || contentType.length() > 128) {
            throw new IllegalArgumentException("contentType must be bounded");
        }
    }

    @Override
    public void close() throws IOException {
        inputStream.close();
    }
}
