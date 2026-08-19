package com.lifeos.notification.endpoint;

import com.lifeos.notification.persistence.NotificationEndpoint;

/** A freshly created or safely replayed endpoint enrollment result. */
public record EndpointRegistrationResult(NotificationEndpoint endpoint, boolean duplicate) {
}
