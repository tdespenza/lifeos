package com.lifeos.profile.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Explicit, revocable settings for future AI personalization. This service records consent and
 * permitted data categories only; it does not send personal data to an AI provider.
 */
@Entity
@Table(name = "profile_ai_personalization_settings")
public class ProfileAiPersonalizationSettings {

    @Id
    @Column(name = "profile_id")
    private UUID profileId;

    @Column(name = "consent_granted", nullable = false)
    private boolean consentGranted;

    @Column(name = "personalization_enabled", nullable = false)
    private boolean personalizationEnabled;

    @Column(name = "allowed_context_categories", nullable = false, length = 160)
    private String allowedContextCategories;

    @Column(name = "consent_updated_at", nullable = false)
    private Instant consentUpdatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ProfileAiPersonalizationSettings() {
        // required by JPA
    }

    public ProfileAiPersonalizationSettings(UUID profileId) {
        this(profileId, false, false, Set.of());
    }

    public ProfileAiPersonalizationSettings(
            UUID profileId, boolean consentGranted, boolean personalizationEnabled, Set<AiContextCategory> categories) {
        this.profileId = Objects.requireNonNull(profileId, "profileId must not be null");
        assign(consentGranted, personalizationEnabled, categories);
        Instant now = Instant.now();
        consentUpdatedAt = now;
        updatedAt = now;
        version = 0L;
    }

    public UUID getProfileId() {
        return profileId;
    }

    public boolean isConsentGranted() {
        return consentGranted;
    }

    public boolean isPersonalizationEnabled() {
        return personalizationEnabled;
    }

    public Set<AiContextCategory> getAllowedContextCategories() {
        if (allowedContextCategories == null || allowedContextCategories.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(allowedContextCategories.split(","))
                .map(AiContextCategory::valueOf)
                .collect(Collectors.toUnmodifiableSet());
    }

    public Instant getConsentUpdatedAt() {
        return consentUpdatedAt;
    }

    public long getVersion() {
        return version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void update(boolean consentGranted, boolean personalizationEnabled, Set<AiContextCategory> categories) {
        assign(consentGranted, personalizationEnabled, categories);
        Instant now = Instant.now();
        consentUpdatedAt = now;
        updatedAt = now;
    }

    private void assign(boolean consentGranted, boolean personalizationEnabled, Set<AiContextCategory> categories) {
        Set<AiContextCategory> safeCategories = categories == null || categories.isEmpty()
                ? Set.of()
                : Set.copyOf(EnumSet.copyOf(categories));
        if ((!consentGranted || !personalizationEnabled) && !safeCategories.isEmpty()) {
            throw new IllegalArgumentException("AI context categories require active AI personalization consent");
        }
        if (personalizationEnabled && !consentGranted) {
            throw new IllegalArgumentException("AI personalization requires explicit consent");
        }
        this.consentGranted = consentGranted;
        this.personalizationEnabled = personalizationEnabled;
        allowedContextCategories = safeCategories.stream()
                .sorted(Comparator.comparing(Enum::name))
                .map(Enum::name)
                .collect(Collectors.joining(","));
    }
}
