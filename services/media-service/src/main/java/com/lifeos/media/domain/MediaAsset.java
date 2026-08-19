package com.lifeos.media.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Protected source-video metadata; bytes and provider paths stay behind the storage boundary. */
@Entity
@Table(
        name = "media_asset",
        indexes = @Index(name = "idx_media_asset_owner_created", columnList = "tenant_id, owner_account_id, created_at, id"))
public class MediaAsset {

    @Id
    private UUID id;

    @Column(name = "owner_account_id", nullable = false, updatable = false)
    private UUID ownerAccountId;

    @Column(name = "tenant_id", nullable = false, length = 255, updatable = false)
    private String tenantId;

    @Column(name = "source_object_reference", length = 160, updatable = false, unique = true)
    private String sourceObjectReference;

    @Column(name = "checksum_sha256", length = 64, updatable = false)
    private String checksumSha256;

    @Column(name = "content_length", updatable = false)
    private Long contentLength;

    @Column(name = "content_type", length = 128, updatable = false)
    private String contentType;

    @Column(nullable = false, length = 140)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 48)
    private MediaAssetStatus status;

    @Column(name = "hls_manifest_reference", length = 160)
    private String hlsManifestReference;

    @Column(name = "processing_failure_code", length = 80)
    private String processingFailureCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected MediaAsset() {
    }

    private MediaAsset(UUID id, UUID ownerAccountId, String tenantId, String title, Instant now) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.ownerAccountId = Objects.requireNonNull(ownerAccountId, "ownerAccountId must not be null");
        this.tenantId = requireText(tenantId, "tenantId", 255);
        this.title = requireText(title, "title", 140);
        status = MediaAssetStatus.AWAITING_UPLOAD;
        createdAt = Objects.requireNonNull(now, "now must not be null");
        updatedAt = now;
    }

    public static MediaAsset pending(UUID id, UUID ownerAccountId, String tenantId, String title, Instant now) {
        return new MediaAsset(id, ownerAccountId, tenantId, title, now);
    }

    /** Binds verified bytes exactly once after the metadata owner has passed the upload policy. */
    public void completeUpload(
            String valueSourceObjectReference,
            String valueChecksumSha256,
            long valueContentLength,
            String valueContentType,
            Instant now) {
        if (status != MediaAssetStatus.AWAITING_UPLOAD) {
            throw new MediaLifecycleTransitionException("upload");
        }
        sourceObjectReference = requireOpaqueReference(valueSourceObjectReference);
        checksumSha256 = requireChecksum(valueChecksumSha256);
        if (valueContentLength < 1) {
            throw new IllegalArgumentException("contentLength must be positive");
        }
        contentLength = valueContentLength;
        contentType = requireText(valueContentType, "contentType", 128);
        status = MediaAssetStatus.STORED_AWAITING_EXTERNAL_PROCESSING;
        updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    /** Reserved for a future worker adapter after it validates a private HLS manifest. */
    public void markHlsReady(String manifestReference, Instant now) {
        if (status != MediaAssetStatus.STORED_AWAITING_EXTERNAL_PROCESSING) {
            throw new MediaLifecycleTransitionException("hls-ready");
        }
        hlsManifestReference = requireOpaqueReference(manifestReference);
        status = MediaAssetStatus.HLS_READY;
        processingFailureCode = null;
        updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    /** Records only a safe worker error code, never ffmpeg/provider output or media bytes. */
    public void markProcessingFailed(String safeCode, Instant now) {
        if (status != MediaAssetStatus.STORED_AWAITING_EXTERNAL_PROCESSING) {
            throw new MediaLifecycleTransitionException("processing-failed");
        }
        if (safeCode == null || !safeCode.matches("[A-Z0-9_]{1,80}")) {
            throw new IllegalArgumentException("safe processing code is invalid");
        }
        status = MediaAssetStatus.PROCESSING_FAILED;
        processingFailureCode = safeCode;
        updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must be nonblank and within its storage bound");
        }
        return value;
    }

    private static String requireOpaqueReference(String value) {
        if (value == null || !value.matches("[a-z][a-z0-9-]{0,31}:[A-Za-z0-9._:-]{1,120}")) {
            throw new IllegalArgumentException("object reference must be a bounded opaque reference");
        }
        return value;
    }

    private static String requireChecksum(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("checksum must be a SHA-256 hex digest");
        }
        return value;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerAccountId() {
        return ownerAccountId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getSourceObjectReference() {
        return sourceObjectReference;
    }

    public String getChecksumSha256() {
        return checksumSha256;
    }

    public Long getContentLength() {
        return contentLength;
    }

    public String getContentType() {
        return contentType;
    }

    public String getTitle() {
        return title;
    }

    public MediaAssetStatus getStatus() {
        return status;
    }

    public String getHlsManifestReference() {
        return hlsManifestReference;
    }

    public String getProcessingFailureCode() {
        return processingFailureCode;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
