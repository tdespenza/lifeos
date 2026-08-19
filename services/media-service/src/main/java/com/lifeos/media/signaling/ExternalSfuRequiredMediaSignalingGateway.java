package com.lifeos.media.signaling;

import com.lifeos.media.config.MediaSignalingMode;
import com.lifeos.media.domain.MediaSession;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Fails closed when deployment requires an SFU but has not installed its workload-authenticated adapter. */
@Component
@ConditionalOnProperty(name = "media.signaling.mode", havingValue = "EXTERNAL_SFU_REQUIRED")
public class ExternalSfuRequiredMediaSignalingGateway implements MediaSignalingGateway {

    @Override
    public MediaSignalingPermit issuePermit(MediaSession session, UUID participantAccountId, Instant now) {
        throw new MediaSignalingUnavailableException();
    }
}
