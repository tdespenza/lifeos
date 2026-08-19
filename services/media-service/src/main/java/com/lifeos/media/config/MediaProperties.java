package com.lifeos.media.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Deployment-owned limits and explicit adapter modes for Media. */
@ConfigurationProperties(prefix = "media")
@Validated
public class MediaProperties {

    @NotNull
    private Duration inboundRequestTimeout = Duration.ofSeconds(30);

    @NotNull
    private Duration uploadDeadline = Duration.ofSeconds(60);

    @Min(1)
    @Max(104_857_600)
    private long maxUploadBytes = 52_428_800L;

    @Min(65_537)
    @Max(105_906_176)
    private long maxInboundBodyBytes = 53_477_376L;

    @Min(1)
    @Max(512)
    private int maxConcurrentRequests = 32;

    @NotBlank
    @Size(min = 32, max = 512)
    private String idempotencySecret;

    @NotBlank
    @Size(min = 32, max = 512)
    private String auditClientFingerprintSecret;

    @NotBlank
    @Size(min = 32, max = 512)
    private String developmentSignalingSecret;

    @Valid
    private final Storage storage = new Storage();

    @Valid
    private final Signaling signaling = new Signaling();

    @Valid
    private final Processing processing = new Processing();

    public Duration getInboundRequestTimeout() {
        return inboundRequestTimeout;
    }

    public void setInboundRequestTimeout(Duration inboundRequestTimeout) {
        this.inboundRequestTimeout = inboundRequestTimeout;
    }

    public Duration getUploadDeadline() {
        return uploadDeadline;
    }

    public void setUploadDeadline(Duration uploadDeadline) {
        this.uploadDeadline = uploadDeadline;
    }

    public long getMaxUploadBytes() {
        return maxUploadBytes;
    }

    public void setMaxUploadBytes(long maxUploadBytes) {
        this.maxUploadBytes = maxUploadBytes;
    }

    public long getMaxInboundBodyBytes() {
        return maxInboundBodyBytes;
    }

    public void setMaxInboundBodyBytes(long maxInboundBodyBytes) {
        this.maxInboundBodyBytes = maxInboundBodyBytes;
    }

    public int getMaxConcurrentRequests() {
        return maxConcurrentRequests;
    }

    public void setMaxConcurrentRequests(int maxConcurrentRequests) {
        this.maxConcurrentRequests = maxConcurrentRequests;
    }

    public String getIdempotencySecret() {
        return idempotencySecret;
    }

    public void setIdempotencySecret(String idempotencySecret) {
        this.idempotencySecret = idempotencySecret;
    }

    public String getAuditClientFingerprintSecret() {
        return auditClientFingerprintSecret;
    }

    public void setAuditClientFingerprintSecret(String auditClientFingerprintSecret) {
        this.auditClientFingerprintSecret = auditClientFingerprintSecret;
    }

    public String getDevelopmentSignalingSecret() {
        return developmentSignalingSecret;
    }

    public void setDevelopmentSignalingSecret(String developmentSignalingSecret) {
        this.developmentSignalingSecret = developmentSignalingSecret;
    }

    public Storage getStorage() {
        return storage;
    }

    public Signaling getSignaling() {
        return signaling;
    }

    public Processing getProcessing() {
        return processing;
    }

    @AssertTrue(message = "Media inbound and upload durations must be between one millisecond and 60 seconds")
    public boolean isTimeoutsValid() {
        return isBoundedDuration(inboundRequestTimeout) && isBoundedDuration(uploadDeadline);
    }

    @AssertTrue(message = "maxInboundBodyBytes must allow one upload plus multipart overhead")
    public boolean isInboundBodyLimitValid() {
        return maxInboundBodyBytes >= maxUploadBytes + 65_536L;
    }

    private static boolean isBoundedDuration(Duration value) {
        return value != null
                && !value.isNegative()
                && !value.isZero()
                && value.compareTo(Duration.ofMillis(1)) >= 0
                && value.compareTo(Duration.ofSeconds(60)) <= 0;
    }

    /** Local development storage exists for reproducible tests and is not a production object-store adapter. */
    @Validated
    public static class Storage {

        @NotNull
        private MediaStorageMode mode = MediaStorageMode.LOCAL_DEVELOPMENT;

        @NotBlank
        private String localRoot = "./var/media-objects";

        public MediaStorageMode getMode() {
            return mode;
        }

        public void setMode(MediaStorageMode mode) {
            this.mode = mode;
        }

        public String getLocalRoot() {
            return localRoot;
        }

        public void setLocalRoot(String localRoot) {
            this.localRoot = localRoot;
        }

        public Path localRootPath() {
            return Path.of(localRoot).toAbsolutePath().normalize();
        }
    }

    /** A bounded credential model, not a substitute for an SFU/WebRTC deployment. */
    @Validated
    public static class Signaling {

        @NotNull
        private MediaSignalingMode mode = MediaSignalingMode.LOCAL_DEVELOPMENT;

        @NotNull
        private Duration credentialTtl = Duration.ofMinutes(2);

        @Min(1)
        @Max(128)
        private int maxConcurrentRequests = 16;

        @NotNull
        private Duration maxSessionDuration = Duration.ofHours(4);

        @NotNull
        private Duration maxScheduleAhead = Duration.ofDays(366);

        public MediaSignalingMode getMode() {
            return mode;
        }

        public void setMode(MediaSignalingMode mode) {
            this.mode = mode;
        }

        public Duration getCredentialTtl() {
            return credentialTtl;
        }

        public void setCredentialTtl(Duration credentialTtl) {
            this.credentialTtl = credentialTtl;
        }

        public int getMaxConcurrentRequests() {
            return maxConcurrentRequests;
        }

        public void setMaxConcurrentRequests(int maxConcurrentRequests) {
            this.maxConcurrentRequests = maxConcurrentRequests;
        }

        public Duration getMaxSessionDuration() {
            return maxSessionDuration;
        }

        public void setMaxSessionDuration(Duration maxSessionDuration) {
            this.maxSessionDuration = maxSessionDuration;
        }

        public Duration getMaxScheduleAhead() {
            return maxScheduleAhead;
        }

        public void setMaxScheduleAhead(Duration maxScheduleAhead) {
            this.maxScheduleAhead = maxScheduleAhead;
        }

        @AssertTrue(message = "signaling credentialTtl must be between one second and five minutes")
        public boolean isCredentialTtlValid() {
            return credentialTtl != null
                    && credentialTtl.compareTo(Duration.ofSeconds(1)) >= 0
                    && credentialTtl.compareTo(Duration.ofMinutes(5)) <= 0;
        }

        @AssertTrue(message = "live sessions must have bounded duration and scheduling horizon")
        public boolean isSessionBoundsValid() {
            return maxSessionDuration != null
                    && maxSessionDuration.compareTo(Duration.ofMinutes(1)) >= 0
                    && maxSessionDuration.compareTo(Duration.ofHours(24)) <= 0
                    && maxScheduleAhead != null
                    && maxScheduleAhead.compareTo(Duration.ofDays(1)) >= 0
                    && maxScheduleAhead.compareTo(Duration.ofDays(731)) <= 0;
        }
    }

    /** HLS processing may use a bounded local ffmpeg process only in explicit development mode. */
    @Validated
    public static class Processing {

        @NotNull
        private MediaProcessingMode mode = MediaProcessingMode.EXTERNAL_WORKER_REQUIRED;

        @NotBlank
        private String executable = "ffmpeg";

        @NotNull
        private Duration timeout = Duration.ofSeconds(60);

        @Min(1)
        @Max(8)
        private int maxConcurrentJobs = 2;

        public MediaProcessingMode getMode() {
            return mode;
        }

        public void setMode(MediaProcessingMode mode) {
            this.mode = mode;
        }

        public String getExecutable() {
            return executable;
        }

        public void setExecutable(String executable) {
            this.executable = executable;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public int getMaxConcurrentJobs() {
            return maxConcurrentJobs;
        }

        public void setMaxConcurrentJobs(int maxConcurrentJobs) {
            this.maxConcurrentJobs = maxConcurrentJobs;
        }

        @AssertTrue(message = "local HLS processing timeout must be between one second and 60 seconds")
        public boolean isTimeoutValid() {
            return timeout != null
                    && timeout.compareTo(Duration.ofSeconds(1)) >= 0
                    && timeout.compareTo(Duration.ofSeconds(60)) <= 0;
        }
    }
}
