package com.lifeos.media.signaling;

import java.time.Instant;

/** Time-limited room-scoped permit; it is not an ICE, DTLS, or WebRTC transport credential. */
public record MediaSignalingPermit(String mode, String signalingEndpoint, String credential, Instant expiresAt) {

    public MediaSignalingPermit {
        if (mode == null || !mode.matches("[A-Z_]{1,40}")) {
            throw new IllegalArgumentException("mode must be bounded");
        }
        if (signalingEndpoint == null || signalingEndpoint.length() > 200 || signalingEndpoint.isBlank()) {
            throw new IllegalArgumentException("signalingEndpoint must be bounded");
        }
        if (credential == null || credential.length() > 512 || credential.isBlank()) {
            throw new IllegalArgumentException("credential must be bounded");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("expiresAt must not be null");
        }
    }

    @Override
    public String toString() {
        return "MediaSignalingPermit[redacted]";
    }
}
