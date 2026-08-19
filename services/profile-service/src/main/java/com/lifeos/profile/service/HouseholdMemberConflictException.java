package com.lifeos.profile.service;

import com.lifeos.profile.idempotency.ProfileMutationRejectedException;

/** Raised for a duplicate relationship or an attempt to alter the immutable owner membership. */
public class HouseholdMemberConflictException extends ProfileMutationRejectedException {
}
