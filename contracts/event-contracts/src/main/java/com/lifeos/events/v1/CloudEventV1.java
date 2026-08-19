package com.lifeos.events.v1;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A compact, strongly typed CloudEvents 1.0 envelope.
 *
 * <p>The Jackson representation maps record components to the corresponding CloudEvents fields.
 * The {@code correlationId} component is the LifeOS extension attribute {@code correlationid}.
 * Event IDs are immutable idempotency keys; they must be retained by every consumer's durable
 * inbox instead of being regenerated during retries.
 *
 * @param <T> versioned event data payload
 */
public record CloudEventV1<T>(
        UUID id,
        String specversion,
        URI source,
        String type,
        String subject,
        Instant time,
        String datacontenttype,
        UUID correlationId,
        T data) {

    private static final int MAX_TYPE_LENGTH = 200;
    private static final int MAX_SUBJECT_LENGTH = 255;

    public CloudEventV1 {
        Objects.requireNonNull(id, "id must not be null");
        if (!EventContract.CLOUD_EVENTS_SPEC_VERSION.equals(specversion)) {
            throw new IllegalArgumentException("specversion must be CloudEvents 1.0");
        }
        validateSource(source);
        requireToken(type, "type", MAX_TYPE_LENGTH);
        requireText(subject, "subject", MAX_SUBJECT_LENGTH);
        Objects.requireNonNull(time, "time must not be null");
        if (!"application/json".equals(datacontenttype)) {
            throw new IllegalArgumentException("datacontenttype must be application/json");
        }
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        Objects.requireNonNull(data, "data must not be null");
    }

    private static void validateSource(URI value) {
        Objects.requireNonNull(value, "source must not be null");
        if (!value.isAbsolute() || value.getRawUserInfo() != null || value.getRawQuery() != null
                || value.getRawFragment() != null) {
            throw new IllegalArgumentException("source must be an absolute URI without user info, query, or fragment");
        }
    }

    static void requireText(String value, String field, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength || containsUnsafeControl(value)) {
            throw new IllegalArgumentException(field + " must be nonblank, bounded, and free of unsafe control characters");
        }
    }

    static void requireToken(String value, String field, int maximumLength) {
        requireText(value, field, maximumLength);
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._/-]*")) {
            throw new IllegalArgumentException(field + " contains unsupported characters");
        }
    }

    static boolean containsUnsafeControl(String value) {
        return value.chars().anyMatch(character -> Character.isISOControl(character)
                && character != '\n'
                && character != '\r'
                && character != '\t');
    }
}
