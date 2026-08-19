package com.lifeos.media.signaling;

import com.lifeos.media.domain.MediaSession;
import java.time.Instant;
import java.util.UUID;

/** Issues bounded join permits only after Media has reauthorized the owner-scoped session. */
public interface MediaSignalingGateway {

    MediaSignalingPermit issuePermit(MediaSession session, UUID participantAccountId, Instant now);
}
