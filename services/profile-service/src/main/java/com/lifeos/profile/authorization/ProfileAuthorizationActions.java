package com.lifeos.profile.authorization;

/** Canonical action strings enforced by Identity's versioned policy and Profile's local scope. */
public final class ProfileAuthorizationActions {

    public static final String PROFILE_CREATE = "profile:create";
    public static final String PROFILE_READ = "profile:read";
    public static final String PROFILE_UPDATE = "profile:update";
    public static final String PREFERENCES_READ = "profile:preferences-read";
    public static final String PREFERENCES_UPDATE = "profile:preferences-update";
    public static final String PRIVACY_READ = "profile:privacy-read";
    public static final String PRIVACY_UPDATE = "profile:privacy-update";
    public static final String AI_PERSONALIZATION_READ = "profile:ai-personalization-read";
    public static final String AI_PERSONALIZATION_UPDATE = "profile:ai-personalization-update";
    public static final String HOUSEHOLD_CREATE = "household:create";
    public static final String HOUSEHOLD_READ = "household:read";
    public static final String HOUSEHOLD_MEMBERS_READ = "household:members-read";
    public static final String HOUSEHOLD_MEMBERS_MANAGE = "household:members-manage";

    private ProfileAuthorizationActions() {
    }
}
