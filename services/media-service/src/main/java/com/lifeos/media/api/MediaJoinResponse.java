package com.lifeos.media.api;

import com.lifeos.media.signaling.MediaSignalingPermit;
import java.time.Instant;
import java.util.UUID;

/** Short-lived room-scoped signaling bootstrap, not an assertion that WebRTC/SFU is available. */
public record MediaJoinResponse(
        UUID sessionId, String mode, String signalingEndpoint, String credential, Instant expiresAt) {

    public static MediaJoinResponse from(UUID sessionId, MediaSignalingPermit permit) {
        return new MediaJoinResponse(
                sessionId, permit.mode(), permit.signalingEndpoint(), permit.credential(), permit.expiresAt());
    }

    @Override
    public String toString() {
        return "MediaJoinResponse[redacted]";
    }
}
