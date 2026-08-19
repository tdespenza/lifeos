package com.lifeos.media.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable, owner-scoped local post-session artifact.
 *
 * <p>The transcript is an explicitly supplied or locally produced bounded artifact. The service
 * never claims that it performed provider-grade speech recognition; its state records the exact
 * deterministic local processing boundary and can be replaced by a reviewed worker later.
 */
@Entity
@Table(name = "media_session_artifact")
public class MediaSessionArtifact {

    @Id
    private UUID id;

    @Column(name = "session_id", nullable = false, updatable = false, unique = true)
    private UUID sessionId;

    @Column(name = "owner_account_id", nullable = false, updatable = false)
    private UUID ownerAccountId;

    @Column(name = "tenant_id", nullable = false, length = 255, updatable = false)
    private String tenantId;

    @Column(nullable = false, length = 32, updatable = false)
    private String transcriptionMode;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String transcript;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String summary;

    @Column(name = "action_items_json", nullable = false, columnDefinition = "TEXT")
    private String actionItemsJson;

    @Column(nullable = false, length = 32, updatable = false)
    private String processingState;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected MediaSessionArtifact() {
    }

    private MediaSessionArtifact(
            UUID id,
            UUID sessionId,
            UUID ownerAccountId,
            String tenantId,
            String transcriptionMode,
            String transcript,
            String summary,
            String actionItemsJson,
            Instant now) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        this.ownerAccountId = Objects.requireNonNull(ownerAccountId, "ownerAccountId must not be null");
        this.tenantId = requireText(tenantId, "tenantId", 255);
        this.transcriptionMode = requireText(transcriptionMode, "transcriptionMode", 32);
        this.transcript = requireText(transcript, "transcript", 65_536);
        this.summary = requireText(summary, "summary", 8_192);
        this.actionItemsJson = requireText(actionItemsJson, "actionItemsJson", 16_384);
        processingState = "READY";
        createdAt = Objects.requireNonNull(now, "now must not be null");
        updatedAt = now;
    }

    public static MediaSessionArtifact ready(
            UUID id,
            UUID sessionId,
            UUID ownerAccountId,
            String tenantId,
            String transcriptionMode,
            String transcript,
            String summary,
            String actionItemsJson,
            Instant now) {
        return new MediaSessionArtifact(
                id, sessionId, ownerAccountId, tenantId, transcriptionMode, transcript, summary, actionItemsJson, now);
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must be nonblank and bounded");
        }
        return value;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public UUID getOwnerAccountId() {
        return ownerAccountId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getTranscriptionMode() {
        return transcriptionMode;
    }

    public String getTranscript() {
        return transcript;
    }

    public String getSummary() {
        return summary;
    }

    public String getActionItemsJson() {
        return actionItemsJson;
    }

    public String getProcessingState() {
        return processingState;
    }

    public long getVersion() {
        return version;
    }
}
