package com.lifeos.profile.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Personal profile representation owned by one account in its personal tenant. */
@Entity
@Table(
        name = "personal_profile",
        indexes = @Index(name = "idx_personal_profile_owner_tenant", columnList = "owner_account_id, tenant_id"))
public class PersonalProfile {

    @Id
    private UUID id;

    @Column(name = "owner_account_id", nullable = false, updatable = false)
    private UUID ownerAccountId;

    @Column(name = "tenant_id", nullable = false, updatable = false, length = 255)
    private String tenantId;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Column(nullable = false, length = 35)
    private String locale;

    @Column(name = "time_zone", nullable = false, length = 64)
    private String timeZone;

    @Column(length = 80)
    private String pronouns;

    @Column(length = 1000)
    private String bio;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PersonalProfile() {
        // required by JPA
    }

    public PersonalProfile(
            UUID id,
            UUID ownerAccountId,
            String tenantId,
            String displayName,
            String locale,
            String timeZone,
            String pronouns,
            String bio) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.ownerAccountId = ownerAccountId;
        this.tenantId = requireBounded(tenantId, 255, "tenantId");
        assign(displayName, locale, timeZone, pronouns, bio);
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        version = 0L;
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

    public String getDisplayName() {
        return displayName;
    }

    public String getLocale() {
        return locale;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public String getPronouns() {
        return pronouns;
    }

    public String getBio() {
        return bio;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void update(String displayName, String locale, String timeZone, String pronouns, String bio) {
        assign(displayName, locale, timeZone, pronouns, bio);
        updatedAt = Instant.now();
    }

    private void assign(String displayName, String locale, String timeZone, String pronouns, String bio) {
        this.displayName = requireBounded(displayName, 120, "displayName");
        this.locale = validateLocale(locale);
        this.timeZone = validateTimeZone(timeZone);
        this.pronouns = normalizeOptional(pronouns, 80, "pronouns");
        this.bio = normalizeOptional(bio, 1000, "bio");
    }

    private static String validateLocale(String value) {
        String normalized = requireBounded(value, 35, "locale");
        Locale locale = Locale.forLanguageTag(normalized);
        if (locale.getLanguage().isBlank() || "und".equalsIgnoreCase(locale.getLanguage())) {
            throw new IllegalArgumentException("locale must be a valid BCP 47 language tag");
        }
        return normalized;
    }

    private static String validateTimeZone(String value) {
        String normalized = requireBounded(value, 64, "timeZone");
        try {
            ZoneId.of(normalized);
            return normalized;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("timeZone must be a valid IANA zone", exception);
        }
    }

    private static String normalizeOptional(String value, int maximumLength, String name) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(name + " exceeds its maximum length");
        }
        return normalized;
    }

    private static String requireBounded(String value, int maximumLength, String name) {
        if (value == null || value.isBlank() || value.trim().length() > maximumLength) {
            throw new IllegalArgumentException(name + " must be a bounded non-blank value");
        }
        return value.trim();
    }
}
