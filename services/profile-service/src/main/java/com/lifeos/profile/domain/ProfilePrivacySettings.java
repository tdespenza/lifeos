package com.lifeos.profile.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Self-owned privacy controls. New profiles are private and opt out of household sharing. */
@Entity
@Table(name = "profile_privacy_settings")
public class ProfilePrivacySettings {

    @Id
    @Column(name = "profile_id")
    private UUID profileId;

    @Enumerated(EnumType.STRING)
    @Column(name = "profile_visibility", nullable = false, length = 16)
    private ProfileVisibility profileVisibility;

    @Column(name = "share_activity_with_household", nullable = false)
    private boolean shareActivityWithHousehold;

    @Column(name = "allow_household_directory", nullable = false)
    private boolean allowHouseholdDirectory;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ProfilePrivacySettings() {
        // required by JPA
    }

    public ProfilePrivacySettings(UUID profileId) {
        this(profileId, ProfileVisibility.PRIVATE, false, false);
    }

    public ProfilePrivacySettings(
            UUID profileId,
            ProfileVisibility profileVisibility,
            boolean shareActivityWithHousehold,
            boolean allowHouseholdDirectory) {
        this.profileId = Objects.requireNonNull(profileId, "profileId must not be null");
        assign(profileVisibility, shareActivityWithHousehold, allowHouseholdDirectory);
        updatedAt = Instant.now();
        version = 0L;
    }

    public UUID getProfileId() {
        return profileId;
    }

    public ProfileVisibility getProfileVisibility() {
        return profileVisibility;
    }

    public boolean isShareActivityWithHousehold() {
        return shareActivityWithHousehold;
    }

    public boolean isAllowHouseholdDirectory() {
        return allowHouseholdDirectory;
    }

    public long getVersion() {
        return version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void update(
            ProfileVisibility profileVisibility,
            boolean shareActivityWithHousehold,
            boolean allowHouseholdDirectory) {
        assign(profileVisibility, shareActivityWithHousehold, allowHouseholdDirectory);
        updatedAt = Instant.now();
    }

    private void assign(
            ProfileVisibility profileVisibility,
            boolean shareActivityWithHousehold,
            boolean allowHouseholdDirectory) {
        this.profileVisibility = Objects.requireNonNull(profileVisibility, "profileVisibility must not be null");
        this.shareActivityWithHousehold = shareActivityWithHousehold;
        this.allowHouseholdDirectory = allowHouseholdDirectory;
    }
}
