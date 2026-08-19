package com.lifeos.identity.auth;

import java.time.Instant;
import java.util.UUID;

/** Non-sensitive metadata shown when a user manages their own passkeys. */
public record PasskeyCredentialSummary(UUID id, Instant createdAt, Instant lastUsedAt) {
}
