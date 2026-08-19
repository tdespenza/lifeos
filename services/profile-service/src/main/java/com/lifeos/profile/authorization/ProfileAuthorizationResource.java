package com.lifeos.profile.authorization;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Trusted resource facts for the Identity decision authority. They are constructed only from an
 * already authenticated subject and locally loaded persistence state, never copied from JSON.
 */
public record ProfileAuthorizationResource(
        String resourceType, String resourceId, String tenantId, Map<String, String> attributes) {

    private static final String PROFILE = "profile";
    private static final String HOUSEHOLD = "household";
    private static final String OWNER_ACCOUNT_ID = "ownerAccountId";
    private static final String RESOURCE_EXISTS = "resourceExists";
    private static final String REQUESTER_ACCOUNT_ID = "requesterAccountId";
    private static final String MISSING_OWNER_ACCOUNT_ID = "00000000-0000-0000-0000-000000000000";

    public ProfileAuthorizationResource {
        if (!(PROFILE.equals(resourceType) || HOUSEHOLD.equals(resourceType))) {
            throw new IllegalArgumentException("unsupported profile authorization resource type");
        }
        if (resourceId == null || resourceId.isBlank() || tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("resourceId and tenantId must not be blank");
        }
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes must not be null"));
        if (PROFILE.equals(resourceType)) {
            validateProfileAttributes(attributes);
        } else if (!attributes.keySet().equals(Set.of(REQUESTER_ACCOUNT_ID))
                || !isUuid(attributes.get(REQUESTER_ACCOUNT_ID))) {
            throw new IllegalArgumentException("household capability must identify only its requester");
        }
    }

    public static ProfileAuthorizationResource forNewProfile(ProfileSubject subject, UUID profileId) {
        return new ProfileAuthorizationResource(
                PROFILE,
                Objects.requireNonNull(profileId, "profileId must not be null").toString(),
                subject.tenantId(),
                Map.of(OWNER_ACCOUNT_ID, subject.accountId().toString()));
    }

    public static ProfileAuthorizationResource forExistingProfile(ProfileSubject subject, UUID profileId) {
        return new ProfileAuthorizationResource(
                PROFILE,
                Objects.requireNonNull(profileId, "profileId must not be null").toString(),
                subject.tenantId(),
                Map.of(OWNER_ACCOUNT_ID, subject.accountId().toString(), RESOURCE_EXISTS, "true"));
    }

    public static ProfileAuthorizationResource forMissingProfile(ProfileSubject subject) {
        return new ProfileAuthorizationResource(
                PROFILE,
                subject.accountId().toString(),
                subject.tenantId(),
                Map.of(OWNER_ACCOUNT_ID, MISSING_OWNER_ACCOUNT_ID, RESOURCE_EXISTS, "false"));
    }

    /**
     * Requests a caller capability from Identity. Household ownership and delegated permissions
     * are intentionally validated by this service against its own immutable local scope.
     */
    public static ProfileAuthorizationResource forHouseholdCapability(ProfileSubject subject, UUID householdId) {
        return new ProfileAuthorizationResource(
                HOUSEHOLD,
                Objects.requireNonNull(householdId, "householdId must not be null").toString(),
                subject.tenantId(),
                Map.of(REQUESTER_ACCOUNT_ID, subject.accountId().toString()));
    }

    private static void validateProfileAttributes(Map<String, String> attributes) {
        if (!attributes.keySet().equals(Set.of(OWNER_ACCOUNT_ID))
                && !attributes.keySet().equals(Set.of(OWNER_ACCOUNT_ID, RESOURCE_EXISTS))) {
            throw new IllegalArgumentException("profile facts contain unsupported attributes");
        }
        if (!isUuid(attributes.get(OWNER_ACCOUNT_ID))) {
            throw new IllegalArgumentException("profile owner must be a UUID");
        }
        if (attributes.containsKey(RESOURCE_EXISTS)
                && !("true".equals(attributes.get(RESOURCE_EXISTS))
                || "false".equals(attributes.get(RESOURCE_EXISTS)))) {
            throw new IllegalArgumentException("resourceExists must be true or false");
        }
    }

    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException | NullPointerException exception) {
            return false;
        }
    }
}
