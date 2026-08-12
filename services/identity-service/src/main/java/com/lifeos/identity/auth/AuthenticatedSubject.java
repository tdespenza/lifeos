package com.lifeos.identity.auth;

import java.util.UUID;

/** Validated subject context passed from the authentication boundary to protected services. */
public record AuthenticatedSubject(UUID accountId, UUID sessionId, String authenticationMethod) {
}
