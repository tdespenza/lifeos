package com.lifeos.documentvault.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Canonical bounded tag encoding for a metadata column, never for file content. */
public final class DocumentTags {

    private static final Pattern VALID_TAG = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N} .-]{0,31}");
    private static final String SEPARATOR = "\u001f";

    private DocumentTags() {
    }

    public static String encode(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        if (values.size() > 10) {
            throw new IllegalArgumentException("at most ten tags are allowed");
        }
        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            String tag = Objects.requireNonNull(value, "tag must not be null").trim().toLowerCase(Locale.ROOT);
            if (!VALID_TAG.matcher(tag).matches()) {
                throw new IllegalArgumentException("tag is not valid");
            }
            if (!normalized.contains(tag)) {
                normalized.add(tag);
            }
        }
        normalized.sort(Comparator.naturalOrder());
        return String.join(SEPARATOR, normalized);
    }

    public static List<String> decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return List.of();
        }
        return List.of(encoded.split(SEPARATOR, -1));
    }
}
