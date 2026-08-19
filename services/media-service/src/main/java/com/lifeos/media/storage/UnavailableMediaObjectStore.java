package com.lifeos.media.storage;

import com.lifeos.media.config.MediaStorageMode;
import java.io.InputStream;
import java.time.Duration;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Fails closed when deployment selects an external store but has not supplied a reviewed adapter. */
@Component
@ConditionalOnProperty(
        name = "media.storage.mode",
        havingValue = "EXTERNAL_OBJECT_STORE_REQUIRED")
public class UnavailableMediaObjectStore implements MediaObjectStore {

    @Override
    public StagedMediaObject stage(InputStream content, MediaContentType contentType, long maximumBytes, Duration deadline) {
        throw new MediaObjectStorageException();
    }

    @Override
    public StoredMediaObject promote(StagedMediaObject staged, UUID assetId) {
        throw new MediaObjectStorageException();
    }

    @Override
    public void discard(StagedMediaObject staged) {
        throw new MediaObjectStorageException();
    }

    @Override
    public void deleteSource(String objectReference) {
        throw new MediaObjectStorageException();
    }

    @Override
    public MediaReadObject openHlsManifest(String manifestReference) {
        throw new MediaObjectStorageException();
    }

    @Override
    public MediaReadObject openHlsSegment(String manifestReference, String segmentName) {
        throw new MediaObjectStorageException();
    }
}
