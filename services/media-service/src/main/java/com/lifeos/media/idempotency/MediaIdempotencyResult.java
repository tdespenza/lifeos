package com.lifeos.media.idempotency;

/** Exact successful response snapshot returned for a fresh command or matching retry. */
public record MediaIdempotencyResult<T>(T body, int status, String location, boolean replayed) {
}
