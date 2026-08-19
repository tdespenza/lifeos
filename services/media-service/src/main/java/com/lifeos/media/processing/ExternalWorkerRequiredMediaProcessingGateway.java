package com.lifeos.media.processing;

import com.lifeos.media.domain.MediaAsset;
import org.springframework.stereotype.Component;

/**
 * Explicit fail-closed processing gateway. Deployments must replace it with an outbox/worker
 * adapter that validates ffmpeg output before calling Media's future worker-facing completion API.
 */
@Component
public class ExternalWorkerRequiredMediaProcessingGateway implements MediaProcessingGateway {

    @Override
    public void requestHlsProcessing(MediaAsset asset) {
        throw new MediaProcessingUnavailableException();
    }
}
