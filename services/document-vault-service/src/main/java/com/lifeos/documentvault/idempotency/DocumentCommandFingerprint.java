package com.lifeos.documentvault.idempotency;

import com.lifeos.documentvault.domain.DocumentMetadata;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Domain-separated content/metadata fingerprints and HMAC-protected client-key digests. */
final class DocumentCommandFingerprint {

    private static final byte[] REQUEST_DOMAIN =
            "lifeos:document-vault:command-request:v1".getBytes(StandardCharsets.UTF_8);

    private DocumentCommandFingerprint() {
    }

    static String keyHash(String key, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
            throw new IllegalStateException("HmacSHA256 is required by the Java runtime", exception);
        }
    }

    static String uploadFingerprint(
            String checksum, long contentLength, String contentType, DocumentMetadata metadata) {
        return fingerprint(
                "UPLOAD",
                checksum,
                Long.toString(contentLength),
                contentType,
                metadata.title(),
                metadata.encodedTags(),
                timestamp(metadata),
                metadata.source().name(),
                metadata.classification().name());
    }

    static String metadataFingerprint(UUID documentId, long expectedVersion, DocumentMetadata metadata) {
        if (documentId == null) {
            throw new IllegalArgumentException("documentId must not be null");
        }
        return fingerprint(
                "METADATA_UPDATE",
                documentId.toString(),
                Long.toString(expectedVersion),
                metadata.title(),
                metadata.encodedTags(),
                timestamp(metadata),
                metadata.source().name(),
                metadata.classification().name());
    }

    static String proofFingerprint(UUID documentId, long documentVersion, String checksumSha256) {
        if (documentId == null) {
            throw new IllegalArgumentException("documentId must not be null");
        }
        return fingerprint(
                "PROOF_REQUEST", documentId.toString(), Long.toString(documentVersion), checksumSha256);
    }

    private static String timestamp(DocumentMetadata metadata) {
        return metadata.documentTimestamp() == null ? "" : metadata.documentTimestamp().toString();
    }

    private static String fingerprint(String... fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(REQUEST_DOMAIN);
            digest.update((byte) 0);
            for (String field : fields) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) ':');
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

}
