package com.lifeos.media.storage;

import java.io.InputStream;
import java.time.Duration;
import java.util.UUID;

/**
 * Object-storage boundary for source video and worker-produced HLS artifacts. No method accepts a
 * client filename, bucket, provider path, or arbitrary HLS segment path.
 */
public interface MediaObjectStore {

    StagedMediaObject stage(InputStream content, MediaContentType contentType, long maximumBytes, Duration deadline);

    StoredMediaObject promote(StagedMediaObject staged, UUID assetId);

    void discard(StagedMediaObject staged);

    void deleteSource(String objectReference);

    MediaReadObject openHlsManifest(String manifestReference);

    MediaReadObject openHlsSegment(String manifestReference, String segmentName);
}
