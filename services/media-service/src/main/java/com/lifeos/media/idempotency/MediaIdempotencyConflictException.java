package com.lifeos.media.idempotency;

/** A retained idempotency key was reused for different immutable command inputs. */
public class MediaIdempotencyConflictException extends RuntimeException {
}
