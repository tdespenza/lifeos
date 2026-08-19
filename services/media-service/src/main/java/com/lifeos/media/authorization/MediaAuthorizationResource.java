package com.lifeos.media.authorization;

import java.util.Map;
import java.util.UUID;

/** Server-loaded resource facts matching Identity's closed Media V2 descriptor shapes. */
public record MediaAuthorizationResource(
        String resourceType, String resourceId, String tenantId, Map<String, String> attributes) {

    private static final String OWNER_ACCOUNT_ID = "ownerAccountId";
    private static final String RESOURCE_EXISTS = "resourceExists";

    public MediaAuthorizationResource {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static MediaAuthorizationResource newAsset(UUID id, MediaSubject subject) {
        return ownedCreate("media-asset", id, subject);
    }

    public static MediaAuthorizationResource existingAsset(UUID id, UUID owner, MediaSubject subject) {
        return ownedObject("media-asset", id, owner, subject);
    }

    public static MediaAuthorizationResource newSession(UUID id, MediaSubject subject) {
        return ownedCreate("media-session", id, subject);
    }

    public static MediaAuthorizationResource existingSession(UUID id, UUID owner, MediaSubject subject) {
        return ownedObject("media-session", id, owner, subject);
    }

    public static MediaAuthorizationResource collection(MediaSubject subject) {
        return new MediaAuthorizationResource("media", null, subject.tenantId(), Map.of());
    }

    private static MediaAuthorizationResource ownedCreate(String type, UUID id, MediaSubject subject) {
        return new MediaAuthorizationResource(
                type, id.toString(), subject.tenantId(), Map.of(OWNER_ACCOUNT_ID, subject.accountId().toString()));
    }

    private static MediaAuthorizationResource ownedObject(String type, UUID id, UUID owner, MediaSubject subject) {
        boolean exists = owner != null;
        UUID safeOwner = exists ? owner : new UUID(0L, 0L);
        return new MediaAuthorizationResource(
                type,
                id.toString(),
                subject.tenantId(),
                Map.of(OWNER_ACCOUNT_ID, safeOwner.toString(), RESOURCE_EXISTS, Boolean.toString(exists)));
    }
}
