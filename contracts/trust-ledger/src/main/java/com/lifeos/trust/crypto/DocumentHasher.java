package com.lifeos.trust.crypto;

import com.lifeos.trust.ProofInputException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * Streams a document into a domain-separated SHA-256 proof without retaining document content.
 *
 * <p>The hash input is {@code domain || metadata-length || canonical-metadata || content}, where
 * all metadata fields are length-prefixed UTF-8. The algorithm reads a fixed 16 KiB buffer, runs in
 * O(B) time for B content bytes, and uses O(1) content memory. Empty and over-limit streams are
 * rejected without returning a partial digest.
 */
public final class DocumentHasher {

    public static final String ALGORITHM = "SHA-256";
    public static final long DEFAULT_MAX_CONTENT_BYTES = 100L * 1024L * 1024L;
    private static final byte[] DOMAIN = "lifeos:document-proof:v1\u0000".getBytes(StandardCharsets.US_ASCII);
    private static final int BUFFER_BYTES = 16 * 1024;

    private DocumentHasher() {
    }

    /** Hashes one readable, non-empty document stream under the standard 100 MiB bound. */
    public static DocumentProof hash(InputStream content, CanonicalDocumentMetadata metadata) throws IOException {
        return hash(content, metadata, DEFAULT_MAX_CONTENT_BYTES);
    }

    /**
     * Hashes one readable, non-empty document stream under a caller-owned positive bound.
     *
     * @param content source stream, never closed by this method
     * @param metadata canonical non-private proof context
     * @param maxContentBytes inclusive content-byte bound
     * @return deterministic document proof
     * @throws IOException if the source cannot be read
     * @throws ProofInputException for absent, empty, or oversized input
     */
    public static DocumentProof hash(InputStream content, CanonicalDocumentMetadata metadata, long maxContentBytes)
            throws IOException {
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(metadata, "metadata must not be null");
        if (maxContentBytes < 1) {
            throw new IllegalArgumentException("maximum document size must be positive");
        }

        MessageDigest digest = sha256();
        byte[] canonicalMetadata = metadata.canonicalBytes();
        digest.update(DOMAIN);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(canonicalMetadata.length).array());
        digest.update(canonicalMetadata);

        byte[] buffer = new byte[BUFFER_BYTES];
        long bytesRead = 0;
        int read;
        while ((read = content.read(buffer)) != -1) {
            if (read == 0) {
                int singleByte = content.read();
                if (singleByte == -1) {
                    break;
                }
                if (bytesRead == maxContentBytes) {
                    throw new ProofInputException("document exceeds the configured content limit");
                }
                digest.update((byte) singleByte);
                bytesRead++;
                continue;
            }
            if (read > maxContentBytes - bytesRead) {
                throw new ProofInputException("document exceeds the configured content limit");
            }
            digest.update(buffer, 0, read);
            bytesRead += read;
        }
        if (bytesRead == 0) {
            throw new ProofInputException("document content must not be empty");
        }
        return new DocumentProof(ALGORITHM, new Hash32(digest.digest()), bytesRead);
    }

    /**
     * Hashes one or two fixed-size digest values with a caller-supplied protocol domain byte
     * prefix. Merkle proof code uses this instead of raw concatenation so the leaf/internal domain
     * boundary remains explicit and JCA-backed.
     */
    public static Hash32 sha256(byte[] prefix, Hash32 left, Hash32 right) {
        MessageDigest digest = sha256();
        digest.update(prefix);
        digest.update(left.bytes());
        if (right != null) {
            digest.update(right.bytes());
        }
        return new Hash32(digest.digest());
    }

    /** Hashes a protocol prefix, one big-endian integer, and one fixed-size digest value. */
    public static Hash32 sha256(byte[] prefix, int value, Hash32 right) {
        MessageDigest digest = sha256();
        digest.update(prefix);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
        digest.update(right.bytes());
        return new Hash32(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable in this Java runtime", exception);
        }
    }
}
