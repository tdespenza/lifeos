package com.lifeos.media.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifeos.media.config.MediaProperties;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Storage-bound tests prove generated paths, binary checks, and HLS traversal denial locally. */
class LocalDevelopmentMediaObjectStoreTest {

    @TempDir
    Path root;

    @Test
    void stagesVerifiedMp4IntoGeneratedPathsAndOnlyOpensBoundedPrivateHlsFiles() throws Exception {
        LocalDevelopmentMediaObjectStore store = store();
        StagedMediaObject staged = store.stage(
                new ByteArrayInputStream(mp4()), MediaContentType.MP4, 1024L, java.time.Duration.ofSeconds(1));
        UUID assetId = UUID.randomUUID();
        StoredMediaObject stored = store.promote(staged, assetId);
        Path hls = root.resolve("hls").resolve(assetId.toString());
        Files.createDirectories(hls);
        Files.writeString(hls.resolve("master.m3u8"), "#EXTM3U\nsegment-001.m4s\n", StandardCharsets.US_ASCII);
        Files.write(hls.resolve("segment-001.m4s"), new byte[] {1, 2, 3});

        assertThat(stored.objectReference()).matches("local:" + assetId + ":[0-9a-f-]{36}");
        try (MediaReadObject manifest = store.openHlsManifest("local-hls:" + assetId);
                MediaReadObject segment = store.openHlsSegment("local-hls:" + assetId, "segment-001.m4s")) {
            assertThat(new String(manifest.inputStream().readAllBytes(), StandardCharsets.US_ASCII)).contains("#EXTM3U");
            assertThat(segment.contentType()).isEqualTo("video/iso.segment");
        }
        assertThatThrownBy(() -> store.openHlsSegment("local-hls:" + assetId, "../secret.ts"))
                .isInstanceOf(MediaHlsNotReadyException.class);
    }

    @Test
    void rejectsInvalidBinarySignaturesWithoutPersistingAClientNamedPath() {
        LocalDevelopmentMediaObjectStore store = store();

        assertThatThrownBy(() -> store.stage(
                        new ByteArrayInputStream("not-a-video".getBytes(StandardCharsets.US_ASCII)),
                        MediaContentType.MP4,
                        1024L,
                        java.time.Duration.ofSeconds(1)))
                .isInstanceOf(UnsupportedMediaContentException.class);
    }

    @Test
    void promotesOnlyBoundedGeneratedHlsArtifacts() throws Exception {
        LocalDevelopmentMediaObjectStore store = store();
        UUID assetId = UUID.randomUUID();
        Path staging = store.createHlsProcessingDirectory(assetId);
        Files.writeString(staging.resolve("master.m3u8"), "#EXTM3U\nsegment-00001.ts\n", StandardCharsets.US_ASCII);
        Files.write(staging.resolve("segment-00001.ts"), new byte[] {1, 2, 3});

        assertThat(store.promoteHlsDirectory(assetId, staging)).isEqualTo("local-hls:" + assetId);
        try (MediaReadObject segment = store.openHlsSegment("local-hls:" + assetId, "segment-00001.ts")) {
            assertThat(segment.contentType()).isEqualTo("video/mp2t");
            assertThat(segment.inputStream().readAllBytes()).containsExactly(1, 2, 3);
        }
    }

    @Test
    void rejectsUnexpectedHlsFilesAndCleansFailedProcessingDirectory() throws Exception {
        LocalDevelopmentMediaObjectStore store = store();
        UUID assetId = UUID.randomUUID();
        Path staging = store.createHlsProcessingDirectory(assetId);
        Files.writeString(staging.resolve("master.m3u8"), "#EXTM3U\n", StandardCharsets.US_ASCII);
        Files.writeString(staging.resolve("unsafe.txt"), "private", StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> store.promoteHlsDirectory(assetId, staging))
                .isInstanceOf(MediaObjectStorageException.class);
        store.discardHlsProcessingDirectory(staging);
        assertThat(Files.exists(staging)).isFalse();
    }

    private LocalDevelopmentMediaObjectStore store() {
        MediaProperties properties = new MediaProperties();
        properties.getStorage().setLocalRoot(root.toString());
        return new LocalDevelopmentMediaObjectStore(
                properties, Clock.fixed(Instant.parse("2026-08-18T12:00:00Z"), ZoneOffset.UTC));
    }

    private static byte[] mp4() {
        return new byte[] {0, 0, 0, 16, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 0, 0};
    }
}
