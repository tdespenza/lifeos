package com.lifeos.profile.service;

import com.lifeos.profile.idempotency.ProfileMutationRejectedException;

/** Deliberately generic missing-or-not-permitted resource outcome to prevent enumeration. */
public class ProfileResourceNotFoundException extends ProfileMutationRejectedException {
}
