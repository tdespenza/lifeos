package com.lifeos.media.processing;

import com.lifeos.media.domain.MediaAsset;

/**
 * Boundary for a future worker queue/ffmpeg adapter. This service never treats a source upload as
 * an HLS conversion and never fabricates a worker completion.
 */
public interface MediaProcessingGateway {

    void requestHlsProcessing(MediaAsset asset);
}
