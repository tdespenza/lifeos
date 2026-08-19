package com.lifeos.identity.auth;

import java.time.Instant;
import java.util.List;

/** Raw recovery codes are returned only once at the authenticated generation boundary. */
public record PasskeyRecoveryResult(List<String> codes, Instant expiresAt) {
}
