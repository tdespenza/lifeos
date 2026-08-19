package com.lifeos.media.signaling;

import com.lifeos.media.config.MediaProperties;
import com.lifeos.media.domain.MediaSession;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Development-only join-permit issuer. It deliberately has no WebSocket, TURN, ICE, SRTP, or SFU
 * operation; the signed permit exists only to exercise the authorization/expiry contract locally.
 */
@Component
@ConditionalOnProperty(
        name = "media.signaling.mode",
        havingValue = "LOCAL_DEVELOPMENT",
        matchIfMissing = true)
public class LocalDevelopmentMediaSignalingGateway implements MediaSignalingGateway {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final Clock clock;
    private final SecretKeySpec key;
    private final MediaProperties.Signaling properties;
    private final Semaphore permits;

    public LocalDevelopmentMediaSignalingGateway(MediaProperties properties, Clock mediaClock) {
        clock = mediaClock;
        key = new SecretKeySpec(
                properties.getDevelopmentSignalingSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
        this.properties = properties.getSignaling();
        permits = new Semaphore(this.properties.getMaxConcurrentRequests(), true);
    }

    @Override
    public MediaSignalingPermit issuePermit(MediaSession session, UUID participantAccountId, Instant now) {
        if (!permits.tryAcquire()) {
            throw new MediaSignalingUnavailableException();
        }
        try {
            if (session == null || participantAccountId == null || now == null) {
                throw new MediaSignalingUnavailableException();
            }
            Instant expiresAt = clock.instant().plus(properties.getCredentialTtl());
            String payload = session.roomId() + "." + participantAccountId + "." + expiresAt.toEpochMilli();
            return new MediaSignalingPermit(
                    "LOCAL_DEVELOPMENT",
                    "local://media-signaling/" + session.roomId(),
                    sign(payload),
                    expiresAt);
        } finally {
            permits.release();
        }
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(key);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new MediaSignalingUnavailableException();
        }
    }
}
