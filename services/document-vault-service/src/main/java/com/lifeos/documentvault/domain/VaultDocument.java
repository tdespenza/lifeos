package com.lifeos.documentvault.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Persistent metadata and opaque object reference; document bytes never enter this entity/table. */
@Entity
@Table(
        name = "vault_document",
        indexes = @Index(
                name = "idx_vault_document_owner_tenant_updated",
                columnList = "owner_account_id, tenant_id, updated_at DESC, id ASC"))
public class VaultDocument {

    @Id
    private UUID id;

    @Column(name = "owner_account_id", nullable = false, updatable = false)
    private UUID ownerAccountId;

    @Column(name = "tenant_id", nullable = false, length = 255, updatable = false)
    private String tenantId;

    @Column(name = "object_reference", nullable = false, length = 160, updatable = false, unique = true)
    private String objectReference;

    @Column(name = "checksum_sha256", nullable = false, length = 64, updatable = false)
    private String checksumSha256;

    @Column(name = "content_length", nullable = false, updatable = false)
    private long contentLength;

    @Column(name = "content_type", nullable = false, length = 128, updatable = false)
    private String contentType;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(name = "metadata_tags", nullable = false, length = 1024)
    private String metadataTags;

    /** Delimited HMAC token digests for bounded plain-text content search; never raw content. */
    @Column(name = "content_search_token_digests", nullable = false, length = 16_640)
    private String contentSearchTokenDigests;

    @Column(name = "document_timestamp")
    private Instant documentTimestamp;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DocumentSource source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DocumentClassification classification;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected VaultDocument() {
        // required by JPA
    }

    public VaultDocument(
            UUID id,
            UUID ownerAccountId,
            String tenantId,
            String objectReference,
            String checksumSha256,
            long contentLength,
            String contentType,
            DocumentMetadata metadata,
            String contentSearchTokenDigests,
            Instant now) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.ownerAccountId = Objects.requireNonNull(ownerAccountId, "ownerAccountId must not be null");
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        this.tenantId = tenantId;
        this.objectReference = requireOpaqueReference(objectReference);
        this.checksumSha256 = requireChecksum(checksumSha256);
        if (contentLength < 1) {
            throw new IllegalArgumentException("contentLength must be positive");
        }
        this.contentLength = contentLength;
        this.contentType = requireContentType(contentType);
        this.createdAt = Objects.requireNonNull(now, "now must not be null");
        this.updatedAt = now;
        this.version = 0L;
        this.contentSearchTokenDigests = requireSearchTokenDigests(contentSearchTokenDigests);
        applyMetadata(metadata, now);
    }

    /** Compatibility constructor for existing callers and non-text object-store adapters. */
    public VaultDocument(
            UUID id,
            UUID ownerAccountId,
            String tenantId,
            String objectReference,
            String checksumSha256,
            long contentLength,
            String contentType,
            DocumentMetadata metadata,
            Instant now) {
        this(id, ownerAccountId, tenantId, objectReference, checksumSha256, contentLength, contentType,
                metadata, "", now);
    }

    public void updateMetadata(DocumentMetadata metadata, Instant now) {
        applyMetadata(metadata, Objects.requireNonNull(now, "now must not be null"));
    }

    private void applyMetadata(DocumentMetadata metadata, Instant now) {
        metadata = Objects.requireNonNull(metadata, "metadata must not be null");
        title = metadata.title();
        metadataTags = metadata.encodedTags();
        documentTimestamp = metadata.documentTimestamp();
        source = metadata.source();
        classification = metadata.classification();
        updatedAt = now;
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

    public String getObjectReference() {
        return objectReference;
    }

    public String getChecksumSha256() {
        return checksumSha256;
    }

    public long getContentLength() {
        return contentLength;
    }

    public String getContentType() {
        return contentType;
    }

    public String getTitle() {
        return title;
    }

    public List<String> getTags() {
        return DocumentTags.decode(metadataTags);
    }

    public String getContentSearchTokenDigests() {
        return contentSearchTokenDigests;
    }

    public Instant getDocumentTimestamp() {
        return documentTimestamp;
    }

    public DocumentSource getSource() {
        return source;
    }

    public DocumentClassification getClassification() {
        return classification;
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

    private static String requireOpaqueReference(String value) {
        // The persistence model deliberately understands only the opaque reference envelope, not
        // any provider path or bucket convention. This preserves the production-adapter boundary.
        if (value == null || !value.matches("[a-z][a-z0-9-]{0,31}:[A-Za-z0-9._:-]{1,120}")) {
            throw new IllegalArgumentException("objectReference must be a bounded opaque reference");
        }
        return value;
    }

    private static String requireChecksum(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("checksumSha256 must be a SHA-256 digest");
        }
        return value;
    }

    private static String requireContentType(String value) {
        if (value == null || value.length() > 128 || value.isBlank()) {
            throw new IllegalArgumentException("contentType must be a bounded non-blank value");
        }
        return value;
    }

    private static String requireSearchTokenDigests(String value) {
        if (value == null || value.length() > 16_640 || !(value.isEmpty() || value.matches(";[0-9a-f;]*"))) {
            throw new IllegalArgumentException("contentSearchTokenDigests must be bounded HMAC tokens");
        }
        return value;
    }
}
