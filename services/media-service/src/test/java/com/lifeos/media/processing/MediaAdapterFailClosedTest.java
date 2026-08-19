package com.lifeos.media.processing;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import com.lifeos.media.config.MediaProcessingMode;
import com.lifeos.media.config.MediaProperties;
import com.lifeos.media.domain.MediaAsset;
import com.lifeos.media.domain.MediaAssetRepository;
import com.lifeos.media.storage.LocalDevelopmentMediaObjectStore;
import com.lifeos.media.storage.MediaContentType;
import com.lifeos.media.storage.StagedMediaObject;
import com.lifeos.media.storage.StoredMediaObject;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFileAttributeView;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/** Guards against accidentally presenting a storage placeholder as an external transcoding worker. */
class MediaAdapterFailClosedTest {

    @TempDir
    Path root;

    @Test
    void externalWorkerPlaceholderNeverClaimsToTranscode() {
        MediaAsset asset = MediaAsset.pending(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID().toString(), "Private clip", Instant.now());

        assertThatThrownBy(() -> new ExternalWorkerRequiredMediaProcessingGateway().requestHlsProcessing(asset))
                .isInstanceOf(MediaProcessingUnavailableException.class);
    }

    @Test
    void localAdapterPromotesOnlyValidatedHlsArtifacts() throws Exception {
        Assumptions.assumeTrue(Files.getFileAttributeView(root, PosixFileAttributeView.class) != null);
        MediaProperties properties = new MediaProperties();
        properties.getStorage().setLocalRoot(root.toString());
        properties.getProcessing().setMode(MediaProcessingMode.LOCAL_DEVELOPMENT);
        properties.getProcessing().setTimeout(java.time.Duration.ofSeconds(5));
        properties.getProcessing().setMaxConcurrentJobs(1);
        Path executable = root.resolve("fake-ffmpeg.sh");
        Files.writeString(
                executable,
                "#!/bin/sh\n"
                        + "manifest=''\n"
                        + "for arg in \"$@\"; do manifest=\"$arg\"; done\n"
                        + "dir=\"$(dirname \"$manifest\")\"\n"
                        + "printf '#EXTM3U\\nsegment-00001.ts\\n' > \"$manifest\"\n"
                        + "printf '\\001' > \"$dir/segment-00001.ts\"\n",
                StandardCharsets.US_ASCII);
        Files.setPosixFilePermissions(
                executable,
                Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        properties.getProcessing().setExecutable(executable.toString());

        LocalDevelopmentMediaObjectStore store = new LocalDevelopmentMediaObjectStore(
                properties, Clock.fixed(Instant.parse("2026-08-19T00:00:00Z"), ZoneOffset.UTC));
        UUID assetId = UUID.randomUUID();
        MediaAsset asset = MediaAsset.pending(assetId, UUID.randomUUID(), UUID.randomUUID().toString(), "Clip", Instant.now());
        StagedMediaObject staged = store.stage(
                new ByteArrayInputStream(mp4()), MediaContentType.MP4, 1024, java.time.Duration.ofSeconds(1));
        StoredMediaObject stored = store.promote(staged, assetId);
        asset.completeUpload(stored.objectReference(), staged.checksumSha256(), staged.contentLength(), "video/mp4", Instant.now());

        MediaAssetRepository repository = Mockito.mock(MediaAssetRepository.class);
        Mockito.when(repository.findByIdForUpdate(assetId)).thenReturn(Optional.of(asset));
        LocalFfmpegMediaProcessingGateway gateway = new LocalFfmpegMediaProcessingGateway(
                store, repository, properties, Clock.systemUTC());
        gateway.requestHlsProcessing(asset);

        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (asset.getStatus() != com.lifeos.media.domain.MediaAssetStatus.HLS_READY
                && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        gateway.shutdown();
        assertThat(asset.getStatus()).isEqualTo(com.lifeos.media.domain.MediaAssetStatus.HLS_READY);
        assertThat(asset.getHlsManifestReference()).isEqualTo("local-hls:" + assetId);
    }

    private static byte[] mp4() {
        return new byte[] {0, 0, 0, 16, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 0, 0};
    }
}
