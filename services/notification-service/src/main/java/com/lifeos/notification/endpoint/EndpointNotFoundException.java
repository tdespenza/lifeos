package com.lifeos.notification.endpoint;

/** Missing and cross-owner endpoint IDs intentionally use the same public result. */
public class EndpointNotFoundException extends RuntimeException {

    public EndpointNotFoundException() {
        super("notification endpoint is not available");
    }
}
