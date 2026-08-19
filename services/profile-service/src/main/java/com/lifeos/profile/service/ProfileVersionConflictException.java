package com.lifeos.profile.service;

import com.lifeos.profile.idempotency.ProfileMutationRejectedException;

/** Raised when a strong ETag no longer represents the target resource. */
public class ProfileVersionConflictException extends ProfileMutationRejectedException {
}
