package com.lifeos.profile.service;

/** Immutable public response metadata/body plus whether it came from a matching stored snapshot. */
public record ProfileMutationResult<T>(T body, boolean replayed, int responseStatus, String responseLocation) {
}
