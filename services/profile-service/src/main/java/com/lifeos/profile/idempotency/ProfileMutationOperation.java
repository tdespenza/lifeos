package com.lifeos.profile.idempotency;

/** All Profile service write operations that require a caller-scoped durable reservation. */
public enum ProfileMutationOperation {
    CREATE_PROFILE,
    UPDATE_PROFILE,
    UPDATE_PREFERENCES,
    UPDATE_PRIVACY,
    UPDATE_AI_PERSONALIZATION,
    CREATE_HOUSEHOLD,
    ADD_HOUSEHOLD_MEMBER,
    UPDATE_HOUSEHOLD_MEMBER,
    REMOVE_HOUSEHOLD_MEMBER
}
