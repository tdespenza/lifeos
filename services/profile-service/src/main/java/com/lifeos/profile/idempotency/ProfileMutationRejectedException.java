package com.lifeos.profile.idempotency;

/**
 * A deterministic business rejection after a durable reservation was claimed but before its
 * mutation transaction could commit. Such a reservation must be removed rather than retained as
 * an indefinitely PENDING retry, unlike a transient persistence failure which remains resumable.
 */
public abstract class ProfileMutationRejectedException extends RuntimeException {

    protected ProfileMutationRejectedException() {
        super();
    }
}
