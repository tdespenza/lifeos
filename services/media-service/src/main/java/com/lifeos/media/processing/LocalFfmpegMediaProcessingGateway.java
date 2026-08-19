package com.lifeos.media.processing;

import com.lifeos.media.config.MediaProperties;
import com.lifeos.media.domain.MediaAsset;
import com.lifeos.media.domain.MediaAssetRepository;
import com.lifeos.media.storage.LocalDevelopmentMediaObjectStore;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Explicit local-development ffmpeg adapter. It is bounded, asynchronous, and never enabled by
 * default; production deployments must provide a reviewed worker/object-store boundary instead.
 */
@Component
@ConditionalOnProperty(name = "media.processing.mode", havingValue = "LOCAL_DEVELOPMENT")
@ConditionalOnProperty(name = "media.storage.mode", havingValue = "LOCAL_DEVELOPMENT", matchIfMissing = true)
final class LocalFfmpegMediaProcessingGateway implements MediaProcessingGateway {

    private final LocalDevelopmentMediaObjectStore objectStore;
    private final MediaAssetRepository assetRepository;
    private final MediaProperties.Processing properties;
    private final Clock clock;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore permits;

    LocalFfmpegMediaProcessingGateway(
            LocalDevelopmentMediaObjectStore objectStore,
            MediaAssetRepository assetRepository,
            MediaProperties properties,
            Clock mediaClock) {
        this.objectStore = objectStore;
        this.assetRepository = assetRepository;
        properties = java.util.Objects.requireNonNull(properties, "properties must not be null");
        this.properties = properties.getProcessing();
        clock = mediaClock;
        permits = new Semaphore(this.properties.getMaxConcurrentJobs(), true);
    }

    @Override
    public void requestHlsProcessing(MediaAsset asset) {
        if (asset == null || !permits.tryAcquire()) {
            markFailed(asset == null ? null : asset.getId(), "PROCESSING_CAPACITY_EXCEEDED");
            return;
        }
        executor.submit(() -> {
            try {
                process(asset.getId(), asset.getSourceObjectReference());
            } finally {
                permits.release();
            }
        });
    }

    private void process(UUID assetId, String sourceReference) {
        Path staging = null;
        Process process = null;
        ExecutorService outputReader = Executors.newSingleThreadExecutor();
        try {
            staging = objectStore.createHlsProcessingDirectory(assetId);
            Path source = objectStore.sourcePathForProcessing(sourceReference);
            Path manifest = staging.resolve("master.m3u8");
            Path segmentPattern = staging.resolve("segment-%05d.ts");
            process = new ProcessBuilder(
                            properties.getExecutable(),
                            "-hide_banner",
                            "-loglevel",
                            "error",
                            "-nostdin",
                            "-y",
                            "-i",
                            source.toString(),
                            "-map_metadata",
                            "-1",
                            "-c:v",
                            "libx264",
                            "-preset",
                            "veryfast",
                            "-c:a",
                            "aac",
                            "-f",
                            "hls",
                            "-hls_time",
                            "6",
                            "-hls_playlist_type",
                            "vod",
                            "-hls_segment_filename",
                            segmentPattern.toString(),
                            manifest.toString())
                    .redirectErrorStream(true)
                    .start();
            Process running = process;
            Future<String> output = outputReader.submit(() -> readBounded(running.getInputStream(), 65_536));
            if (!process.waitFor(properties.getTimeout().toMillis(), TimeUnit.MILLISECONDS)
                    || process.exitValue() != 0) {
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
                output.cancel(true);
                markFailed(assetId, "FFMPEG_FAILED");
                return;
            }
            output.get(500, TimeUnit.MILLISECONDS);
            String reference = objectStore.promoteHlsDirectory(assetId, staging);
            assetRepository.findByIdForUpdate(assetId).ifPresent(current -> {
                current.markHlsReady(reference, clock.instant());
                assetRepository.saveAndFlush(current);
            });
        } catch (IOException | InterruptedException | java.util.concurrent.ExecutionException
                | java.util.concurrent.TimeoutException | RuntimeException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            if (staging != null) {
                objectStore.discardHlsProcessingDirectory(staging);
            }
            markFailed(assetId, "FFMPEG_FAILED");
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            outputReader.shutdownNow();
        }
    }

    private void markFailed(UUID assetId, String code) {
        if (assetId == null) {
            return;
        }
        try {
            assetRepository.findByIdForUpdate(assetId).ifPresent(current -> {
                try {
                    current.markProcessingFailed(code, clock.instant());
                    assetRepository.saveAndFlush(current);
                } catch (RuntimeException ignored) {
                    // A concurrent lifecycle transition remains the source of truth.
                }
            });
        } catch (RuntimeException ignored) {
            // Reconciliation can inspect the stored source and safe failure state later.
        }
    }

    private static String readBounded(InputStream input, int maximumBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(8_192);
        byte[] buffer = new byte[4_096];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) {
                continue;
            }
            if (output.size() > maximumBytes - read) {
                return "";
            }
            output.write(buffer, 0, read);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
