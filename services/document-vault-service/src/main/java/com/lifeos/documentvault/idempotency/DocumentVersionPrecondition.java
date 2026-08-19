package com.lifeos.documentvault.idempotency;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the one accepted strong numeric ETag format for metadata writes. */
public final class DocumentVersionPrecondition {

    public static final String HEADER_NAME = "If-Match";
    private static final Pattern STRONG_NUMERIC_ETAG = Pattern.compile("^\\\"([0-9]{1,19})\\\"$");

    private DocumentVersionPrecondition() {
    }

    public static long requireSingleHeader(List<String> values) {
        if (values == null || values.isEmpty()) {
            throw new DocumentVersionPreconditionRequiredException();
        }
        if (values.size() != 1) {
            throw new InvalidDocumentVersionPreconditionException();
        }
        Matcher matcher = STRONG_NUMERIC_ETAG.matcher(values.getFirst());
        if (!matcher.matches()) {
            throw new InvalidDocumentVersionPreconditionException();
        }
        try {
            long version = Long.parseLong(matcher.group(1));
            if (version < 0) {
                throw new InvalidDocumentVersionPreconditionException();
            }
            return version;
        } catch (NumberFormatException exception) {
            throw new InvalidDocumentVersionPreconditionException();
        }
    }
}
