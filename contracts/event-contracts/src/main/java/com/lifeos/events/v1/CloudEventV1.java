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
 * This LifeOS profile requires {@code subject} to be non-null, non-blank, and single-line even
 * though CloudEvents 1.0 defines the attribute as optional; producers cannot omit it.
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
    private static final int MAX_SOURCE_LENGTH = 2048;

    public CloudEventV1 {
        Objects.requireNonNull(id, "id must not be null");
        if (!EventContract.CLOUD_EVENTS_SPEC_VERSION.equals(specversion)) {
            throw new IllegalArgumentException("specversion must be CloudEvents 1.0");
        }
        validateSource(source);
        EventText.requireToken(type, "type", MAX_TYPE_LENGTH);
        EventText.requireSingleLine(subject, "subject", MAX_SUBJECT_LENGTH);
        Objects.requireNonNull(time, "time must not be null");
        if (!"application/json".equals(datacontenttype)) {
            throw new IllegalArgumentException("datacontenttype must be application/json");
        }
        if (correlationId == null) {
            throw new IllegalArgumentException("correlationId must not be null");
        }
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
    }

    private static void validateSource(URI value) {
        Objects.requireNonNull(value, "source must not be null");
        if (value.toASCIIString().length() > MAX_SOURCE_LENGTH
                || !value.isAbsolute() || value.getRawUserInfo() != null || value.getRawQuery() != null
                || value.getRawFragment() != null) {
            throw new IllegalArgumentException(
                    "source must be an absolute URI of at most 2048 characters without user info, query, or fragment");
        }
    }

}
