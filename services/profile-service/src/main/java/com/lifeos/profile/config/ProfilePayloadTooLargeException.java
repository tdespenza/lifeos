package com.lifeos.profile.config;

import java.io.IOException;

/** Raised when a declared or streaming direct-service request exceeds its configured body bound. */
public class ProfilePayloadTooLargeException extends IOException {

    public ProfilePayloadTooLargeException() {
        super("Profile request body exceeds the configured limit");
    }
}
