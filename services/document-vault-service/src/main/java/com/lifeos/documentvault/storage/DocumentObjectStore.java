package com.lifeos.documentvault.storage;

import java.io.InputStream;
import java.time.Duration;
import java.util.UUID;

/**
 * Object storage boundary. No method accepts a client filename or path, and Postgres receives only
 * the resulting opaque reference plus verified content facts.
 */
public interface DocumentObjectStore {

    StagedDocumentObject stage(
            InputStream content, DocumentContentType contentType, long maxBytes, Duration deadline);

    StoredDocumentObject promote(StagedDocumentObject staged, UUID documentId);

    void discard(StagedDocumentObject staged);

    void delete(String objectReference);
}
