package com.lifeos.notification.access;

import java.util.Objects;
import java.util.UUID;

/** Identity-validated public API subject. It never renders an access token or proof. */
public record NotificationSubject(UUID accountId, UUID sessionId, String authenticationMethod) {

    public NotificationSubject {
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (authenticationMethod == null || authenticationMethod.isBlank()) {
            throw new IllegalArgumentException("authenticationMethod must not be blank");
        }
    }

    @Override
    public String toString() {
        return "NotificationSubject[redacted]";
    }
}
