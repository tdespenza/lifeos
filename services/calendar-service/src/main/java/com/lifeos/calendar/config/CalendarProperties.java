package com.lifeos.calendar.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Deployment-owned bounds, scheduler settings, and digest secrets for Calendar. */
@ConfigurationProperties(prefix = "calendar")
@Validated
public class CalendarProperties {

    @NotBlank(message = "idempotencySecret (CALENDAR_IDEMPOTENCY_SECRET) must be configured")
    private String idempotencySecret;

    @NotBlank(message = "auditClientFingerprintSecret must be configured")
    private String auditClientFingerprintSecret;

    @NotNull
    private Duration inboundRequestTimeout = Duration.ofSeconds(10);

    @Min(1)
    @Max(1_048_576)
    private long maxInboundBodyBytes = 65_536L;

    @Min(1)
    @Max(4096)
    private int maxConcurrentRequests = 128;

    @Valid
    private final Recurrence recurrence = new Recurrence();

    @Valid
    private final Reminders reminders = new Reminders();

    @Valid
    private final Outbox outbox = new Outbox();

    @Valid
    private final TaskGoalProjection taskGoalProjection = new TaskGoalProjection();

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

    public Duration getInboundRequestTimeout() {
        return inboundRequestTimeout;
    }

    public void setInboundRequestTimeout(Duration inboundRequestTimeout) {
        this.inboundRequestTimeout = inboundRequestTimeout;
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

    public Recurrence getRecurrence() {
        return recurrence;
    }

    public Reminders getReminders() {
        return reminders;
    }

    public Outbox getOutbox() {
        return outbox;
    }

    public TaskGoalProjection getTaskGoalProjection() {
        return taskGoalProjection;
    }

    @AssertTrue(message = "inboundRequestTimeout must be between one millisecond and 60 seconds")
    public boolean isInboundRequestTimeoutValid() {
        return inboundRequestTimeout != null
                && !inboundRequestTimeout.isNegative()
                && !inboundRequestTimeout.isZero()
                && inboundRequestTimeout.compareTo(Duration.ofMillis(1)) >= 0
                && inboundRequestTimeout.compareTo(Duration.ofSeconds(60)) <= 0;
    }

    /** Bound recurrence work before an individual calendar series can monopolize workers. */
    @Validated
    public static class Recurrence {

        private boolean materializerEnabled = true;

        @Min(1)
        @Max(90)
        private int horizonDays = 30;

        @Min(1)
        @Max(1_000)
        private int maxOccurrencesPerEvent = 100;

        @Min(1)
        @Max(500)
        private int batchSize = 50;

        @NotNull
        private Duration pollDelay = Duration.ofMinutes(1);

        public boolean isMaterializerEnabled() {
            return materializerEnabled;
        }

        public void setMaterializerEnabled(boolean materializerEnabled) {
            this.materializerEnabled = materializerEnabled;
        }

        public int getHorizonDays() {
            return horizonDays;
        }

        public void setHorizonDays(int horizonDays) {
            this.horizonDays = horizonDays;
        }

        public int getMaxOccurrencesPerEvent() {
            return maxOccurrencesPerEvent;
        }

        public void setMaxOccurrencesPerEvent(int maxOccurrencesPerEvent) {
            this.maxOccurrencesPerEvent = maxOccurrencesPerEvent;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public Duration getPollDelay() {
            return pollDelay;
        }

        public void setPollDelay(Duration pollDelay) {
            this.pollDelay = pollDelay;
        }
    }

    /** Bounds the number of due reminder rows one scheduler invocation may reserve. */
    @Validated
    public static class Reminders {

        private boolean schedulerEnabled = true;

        @Min(1)
        @Max(500)
        private int batchSize = 50;

        @NotNull
        private Duration pollDelay = Duration.ofSeconds(1);

        @NotNull
        private Duration leaseDuration = Duration.ofSeconds(30);

        public boolean isSchedulerEnabled() {
            return schedulerEnabled;
        }

        public void setSchedulerEnabled(boolean schedulerEnabled) {
            this.schedulerEnabled = schedulerEnabled;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public Duration getPollDelay() {
            return pollDelay;
        }

        public void setPollDelay(Duration pollDelay) {
            this.pollDelay = pollDelay;
        }

        public Duration getLeaseDuration() {
            return leaseDuration;
        }

        public void setLeaseDuration(Duration leaseDuration) {
            this.leaseDuration = leaseDuration;
        }
    }

    /** Retry and lease bounds for the durable producer outbox. */
    @Validated
    public static class Outbox {

        private boolean relayEnabled = true;

        @Min(1)
        @Max(500)
        private int batchSize = 100;

        @Min(1)
        @Max(32)
        private int maxConcurrentPublishes = 16;

        @NotNull
        private Duration pollDelay = Duration.ofSeconds(1);

        @NotNull
        private Duration leaseDuration = Duration.ofSeconds(30);

        @Min(1)
        @Max(100)
        private int maxAttempts = 10;

        @NotNull
        private Duration initialBackoff = Duration.ofSeconds(1);

        @NotNull
        private Duration maxBackoff = Duration.ofMinutes(5);

        public boolean isRelayEnabled() {
            return relayEnabled;
        }

        public void setRelayEnabled(boolean relayEnabled) {
            this.relayEnabled = relayEnabled;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getMaxConcurrentPublishes() {
            return maxConcurrentPublishes;
        }

        public void setMaxConcurrentPublishes(int maxConcurrentPublishes) {
            this.maxConcurrentPublishes = maxConcurrentPublishes;
        }

        public Duration getPollDelay() {
            return pollDelay;
        }

        public void setPollDelay(Duration pollDelay) {
            this.pollDelay = pollDelay;
        }

        public Duration getLeaseDuration() {
            return leaseDuration;
        }

        public void setLeaseDuration(Duration leaseDuration) {
            this.leaseDuration = leaseDuration;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Duration getInitialBackoff() {
            return initialBackoff;
        }

        public void setInitialBackoff(Duration initialBackoff) {
            this.initialBackoff = initialBackoff;
        }

        public Duration getMaxBackoff() {
            return maxBackoff;
        }

        public void setMaxBackoff(Duration maxBackoff) {
            this.maxBackoff = maxBackoff;
        }

        @AssertTrue(message = "outbox durations must be positive, bounded, and ordered")
        public boolean isTimingValid() {
            return isPositiveAtMostOneHour(pollDelay)
                    && isPositiveAtMostOneHour(leaseDuration)
                    && isPositiveAtMostOneHour(initialBackoff)
                    && isPositiveAtMostOneHour(maxBackoff)
                    && maxBackoff.compareTo(initialBackoff) >= 0;
        }

        private static boolean isPositiveAtMostOneHour(Duration value) {
            return value != null
                    && value.compareTo(Duration.ZERO) > 0
                    && value.compareTo(Duration.ofHours(1)) <= 0;
        }
    }

    /** Bounded workload-authenticated ownership projection used for linked Task/Goal blocks. */
    @Validated
    public static class TaskGoalProjection {

        private String baseUrl = "http://localhost:8082";
        private String workloadIdentity = "calendar-service";
        private String workloadToken = "";

        @NotNull
        private Duration connectTimeout = Duration.ofSeconds(2);

        @NotNull
        private Duration readTimeout = Duration.ofSeconds(3);

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getWorkloadIdentity() {
            return workloadIdentity;
        }

        public void setWorkloadIdentity(String workloadIdentity) {
            this.workloadIdentity = workloadIdentity;
        }

        public String getWorkloadToken() {
            return workloadToken;
        }

        public void setWorkloadToken(String workloadToken) {
            this.workloadToken = workloadToken;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }

        public boolean configured() {
            return baseUrl != null && !baseUrl.isBlank()
                    && workloadIdentity != null && !workloadIdentity.isBlank()
                    && workloadToken != null && !workloadToken.isBlank();
        }
    }
}
