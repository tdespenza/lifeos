package com.lifeos.documentvault.idempotency;

import com.lifeos.documentvault.domain.DocumentMetadata;
import java.util.UUID;

/** Publicly narrow bridge so command orchestration never exposes key-hash internals. */
public final class DocumentCommandFingerprintAccess {

    private DocumentCommandFingerprintAccess() {
    }

    public static String upload(String checksum, long contentLength, String contentType, DocumentMetadata metadata) {
        return DocumentCommandFingerprint.uploadFingerprint(checksum, contentLength, contentType, metadata);
    }

    public static String metadata(UUID documentId, long expectedVersion, DocumentMetadata metadata) {
        return DocumentCommandFingerprint.metadataFingerprint(documentId, expectedVersion, metadata);
    }

    public static String proof(UUID documentId, long documentVersion, String checksumSha256) {
        return DocumentCommandFingerprint.proofFingerprint(documentId, documentVersion, checksumSha256);
    }

    public static String keyHash(String key, String secret) {
        return DocumentCommandFingerprint.keyHash(key, secret);
    }
}
