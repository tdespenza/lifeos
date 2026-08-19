package com.lifeos.trust.crypto;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;

/**
 * Non-private, canonical metadata bound into a document digest.
 *
 * <p>Names, descriptions, account identifiers, paths, and arbitrary user metadata are deliberately
 * excluded. A service can retain those locally, but this proof input contains only a versioned
 * media type and semantic proof purpose so the same bytes cannot be replayed under another format
 * or proof context.
 */
public record CanonicalDocumentMetadata(String mediaType, String proofPurpose) {

    private static final int MAX_MEDIA_TYPE_LENGTH = 127;
    private static final int MAX_PURPOSE_LENGTH = 64;

    public CanonicalDocumentMetadata {
        requireToken(mediaType, "mediaType", MAX_MEDIA_TYPE_LENGTH);
        requireToken(proofPurpose, "proofPurpose", MAX_PURPOSE_LENGTH);
        mediaType = mediaType.toLowerCase(Locale.ROOT);
        proofPurpose = proofPurpose.toLowerCase(Locale.ROOT);
    }

    /** Returns a length-prefixed UTF-8 binary encoding that is unambiguous across field boundaries. */
    byte[] canonicalBytes() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(1);
                writeText(output, mediaType);
                writeText(output, proofPurpose);
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            // ByteArrayOutputStream cannot throw while writing; retain a controlled invariant if
            // the JDK implementation ever changes rather than silently hashing partial metadata.
            throw new IllegalStateException("canonical metadata encoding failed", exception);
        }
    }

    private static void writeText(DataOutputStream output, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    private static void requireToken(String value, String field, int maximumLength) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank() || value.length() > maximumLength || !value.matches("[A-Za-z0-9][A-Za-z0-9.+/_-]*")) {
            throw new IllegalArgumentException(field + " must be a bounded safe token");
        }
    }
}
