package com.lifeos.profile.idempotency;

/** Exact stored public result plus whether it came from a matching durable retry reservation. */
public record ProfileIdempotencyExecution<T>(
        T value, boolean replayed, int responseStatus, String responseLocation) {
}
