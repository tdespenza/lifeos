package com.lifeos.events.v1;

/**
 * Stable names shared by LifeOS event producers and consumers.
 *
 * <p>Versions are part of both the topic and the CloudEvents type. A breaking payload change must
 * introduce a new type and topic rather than reinterpret an existing record.
 */
public final class EventContract {

    /** CloudEvents specification version carried by every envelope. */
    public static final String CLOUD_EVENTS_SPEC_VERSION = "1.0";

    /** Versioned CloudEvents type for a request to create and deliver a notification. */
    public static final String NOTIFICATION_REQUESTED_V1_TYPE = "com.lifeos.notification.requested.v1";

    /** Kafka-compatible destination for {@link #NOTIFICATION_REQUESTED_V1_TYPE}. */
    public static final String NOTIFICATION_REQUESTED_V1_TOPIC = "lifeos.notification.requested.v1";

    /**
     * Versioned CloudEvents type for a notification request that carries the event's IANA time
     * zone. V1 remains supported unchanged for existing producers and consumers.
     */
    public static final String NOTIFICATION_REQUESTED_V2_TYPE = "com.lifeos.notification.requested.v2";

    /** Kafka-compatible destination for {@link #NOTIFICATION_REQUESTED_V2_TYPE}. */
    public static final String NOTIFICATION_REQUESTED_V2_TOPIC = "lifeos.notification.requested.v2";

    /** Versioned CloudEvents type emitted after an individual channel reaches an outcome. */
    public static final String NOTIFICATION_DELIVERY_STATUS_V1_TYPE =
            "com.lifeos.notification.delivery-status.v1";

    /** Kafka-compatible destination for {@link #NOTIFICATION_DELIVERY_STATUS_V1_TYPE}. */
    public static final String NOTIFICATION_DELIVERY_STATUS_V1_TOPIC =
            "lifeos.notification.delivery-status.v1";

    /** Versioned CloudEvents type for an owner-authorized document proof request. */
    public static final String DOCUMENT_PROOF_REQUESTED_V1_TYPE =
            "com.lifeos.document.proof.requested.v1";

    /** Topic carrying durable Document Vault proof requests to Trust Ledger. */
    public static final String DOCUMENT_PROOF_REQUESTED_V1_TOPIC =
            "lifeos.document.proof.requested.v1";

    /** Versioned CloudEvents type for a privacy-minimized AI audit commitment. */
    public static final String AI_AUDIT_HASH_REQUESTED_V1_TYPE =
            "com.lifeos.ai.audit.hash.requested.v1";

    /** Topic carrying durable AI audit commitments to a future Trust Ledger worker. */
    public static final String AI_AUDIT_HASH_REQUESTED_V1_TOPIC =
            "lifeos.ai.audit.hash.requested.v1";

    private EventContract() {
    }
}
