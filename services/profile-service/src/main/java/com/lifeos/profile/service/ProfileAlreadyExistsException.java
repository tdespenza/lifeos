package com.lifeos.profile.service;

import com.lifeos.profile.idempotency.ProfileMutationRejectedException;

/** Raised when a create-only profile request targets an existing caller/tenant scope. */
public class ProfileAlreadyExistsException extends ProfileMutationRejectedException {
}
