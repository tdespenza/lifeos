package com.lifeos.documentvault.storage;

import java.util.Set;
import java.util.Locale;
import org.springframework.http.MediaType;

/** Closed media-type allow-list for the foundation; arbitrary executable/archive types are refused. */
public enum DocumentContentType {
    PDF("application/pdf"),
    PLAIN_TEXT("text/plain"),
    CSV("text/csv"),
    MARKDOWN("text/markdown"),
    HTML("text/html"),
    PNG("image/png"),
    JPEG("image/jpeg"),
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    PPTX("application/vnd.openxmlformats-officedocument.presentationml.presentation"),
    XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private static final Set<String> ALLOWED = Set.of(
            PDF.mediaType,
            PLAIN_TEXT.mediaType,
            CSV.mediaType,
            MARKDOWN.mediaType,
            HTML.mediaType,
            PNG.mediaType,
            JPEG.mediaType,
            DOCX.mediaType,
            PPTX.mediaType,
            XLSX.mediaType);

    private final String mediaType;

    DocumentContentType(String mediaType) {
        this.mediaType = mediaType;
    }

    public String mediaType() {
        return mediaType;
    }

    public static DocumentContentType requireAllowed(String rawContentType) {
        if (rawContentType == null || rawContentType.isBlank()) {
            throw new UnsupportedDocumentMediaTypeException();
        }
        try {
            MediaType parsed = MediaType.parseMediaType(rawContentType);
            String normalized = parsed.getType().toLowerCase(Locale.ROOT)
                    + "/"
                    + parsed.getSubtype().toLowerCase(Locale.ROOT);
            if (!ALLOWED.contains(normalized)) {
                throw new UnsupportedDocumentMediaTypeException();
            }
            return switch (normalized) {
                case "application/pdf" -> PDF;
                case "text/plain" -> PLAIN_TEXT;
                case "text/csv" -> CSV;
                case "text/markdown" -> MARKDOWN;
                case "text/html" -> HTML;
                case "image/png" -> PNG;
                case "image/jpeg" -> JPEG;
                case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> DOCX;
                case "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> PPTX;
                case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> XLSX;
                default -> throw new UnsupportedDocumentMediaTypeException();
            };
        } catch (IllegalArgumentException exception) {
            throw new UnsupportedDocumentMediaTypeException();
        }
    }

    boolean isTextLike() {
        return this == PLAIN_TEXT || this == CSV || this == MARKDOWN || this == HTML;
    }
}
