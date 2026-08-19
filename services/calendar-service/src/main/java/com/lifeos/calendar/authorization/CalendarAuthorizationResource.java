package com.lifeos.calendar.authorization;

import java.util.Map;
import java.util.UUID;

/** Trusted Calendar resource facts sent to Identity; no client may supply these attributes. */
public record CalendarAuthorizationResource(
        String resourceType, String resourceId, String tenantId, Map<String, String> attributes) {

    private static final String OWNER_ACCOUNT_ID = "ownerAccountId";
    private static final String RESOURCE_EXISTS = "resourceExists";

    public CalendarAuthorizationResource {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static CalendarAuthorizationResource newEvent(UUID id, CalendarSubject subject) {
        return new CalendarAuthorizationResource(
                "calendar-event", id.toString(), subject.tenantId(), Map.of(OWNER_ACCOUNT_ID, subject.accountId().toString()));
    }

    public static CalendarAuthorizationResource existingEvent(UUID id, UUID owner, CalendarSubject subject) {
        return existing("calendar-event", id, owner, subject);
    }

    public static CalendarAuthorizationResource newTimeBlock(UUID id, CalendarSubject subject) {
        return new CalendarAuthorizationResource(
                "calendar-time-block", id.toString(), subject.tenantId(), Map.of(OWNER_ACCOUNT_ID, subject.accountId().toString()));
    }

    public static CalendarAuthorizationResource existingTimeBlock(UUID id, UUID owner, CalendarSubject subject) {
        return existing("calendar-time-block", id, owner, subject);
    }

    public static CalendarAuthorizationResource collection(CalendarSubject subject) {
        return new CalendarAuthorizationResource("calendar", null, subject.tenantId(), Map.of());
    }

    private static CalendarAuthorizationResource existing(
            String type, UUID id, UUID owner, CalendarSubject subject) {
        boolean exists = owner != null;
        UUID safeOwner = exists ? owner : new UUID(0L, 0L);
        return new CalendarAuthorizationResource(
                type,
                id.toString(),
                subject.tenantId(),
                Map.of(OWNER_ACCOUNT_ID, safeOwner.toString(), RESOURCE_EXISTS, Boolean.toString(exists)));
    }
}
