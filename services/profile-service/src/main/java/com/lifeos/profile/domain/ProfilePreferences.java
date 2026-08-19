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

/** Per-profile, validated product preferences. */
@Entity
@Table(name = "profile_preferences")
public class ProfilePreferences {

    @Id
    @Column(name = "profile_id")
    private UUID profileId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ProfileTheme theme;

    @Enumerated(EnumType.STRING)
    @Column(name = "week_start", nullable = false, length = 16)
    private WeekStart weekStart;

    @Column(name = "daily_digest_enabled", nullable = false)
    private boolean dailyDigestEnabled;

    @Column(name = "default_goal_horizon_days", nullable = false)
    private int defaultGoalHorizonDays;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ProfilePreferences() {
        // required by JPA
    }

    public ProfilePreferences(UUID profileId) {
        this(profileId, ProfileTheme.SYSTEM, WeekStart.MONDAY, true, 30);
    }

    public ProfilePreferences(
            UUID profileId,
            ProfileTheme theme,
            WeekStart weekStart,
            boolean dailyDigestEnabled,
            int defaultGoalHorizonDays) {
        this.profileId = Objects.requireNonNull(profileId, "profileId must not be null");
        assign(theme, weekStart, dailyDigestEnabled, defaultGoalHorizonDays);
        updatedAt = Instant.now();
        version = 0L;
    }

    public UUID getProfileId() {
        return profileId;
    }

    public ProfileTheme getTheme() {
        return theme;
    }

    public WeekStart getWeekStart() {
        return weekStart;
    }

    public boolean isDailyDigestEnabled() {
        return dailyDigestEnabled;
    }

    public int getDefaultGoalHorizonDays() {
        return defaultGoalHorizonDays;
    }

    public long getVersion() {
        return version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void update(
            ProfileTheme theme, WeekStart weekStart, boolean dailyDigestEnabled, int defaultGoalHorizonDays) {
        assign(theme, weekStart, dailyDigestEnabled, defaultGoalHorizonDays);
        updatedAt = Instant.now();
    }

    private void assign(
            ProfileTheme theme, WeekStart weekStart, boolean dailyDigestEnabled, int defaultGoalHorizonDays) {
        this.theme = Objects.requireNonNull(theme, "theme must not be null");
        this.weekStart = Objects.requireNonNull(weekStart, "weekStart must not be null");
        if (defaultGoalHorizonDays < 1 || defaultGoalHorizonDays > 365) {
            throw new IllegalArgumentException("defaultGoalHorizonDays must be between 1 and 365");
        }
        this.dailyDigestEnabled = dailyDigestEnabled;
        this.defaultGoalHorizonDays = defaultGoalHorizonDays;
    }
}
