package com.lifeos.assistant.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Bounded, opt-in relay controls for hash-only AI audit commitments. */
@ConfigurationProperties(prefix = "ai-assistant.audit-outbox")
@Validated
public class AiAuditOutboxProperties {

    private boolean relayEnabled;
    @NotNull private Duration pollDelay = Duration.ofSeconds(1);
    @NotNull private Duration leaseDuration = Duration.ofSeconds(30);
    @NotNull private Duration initialBackoff = Duration.ofSeconds(1);
    @NotNull private Duration maxBackoff = Duration.ofMinutes(1);
    @NotNull private Duration publishTimeout = Duration.ofSeconds(5);
    @Min(1) @Max(1000) private int batchSize = 32;
    @Min(1) @Max(128) private int maxConcurrentPublishes = 4;
    @Min(1) @Max(20) private int maxAttempts = 5;

    public boolean isRelayEnabled() { return relayEnabled; }
    public void setRelayEnabled(boolean relayEnabled) { this.relayEnabled = relayEnabled; }
    public Duration getPollDelay() { return pollDelay; }
    public void setPollDelay(Duration pollDelay) { this.pollDelay = pollDelay; }
    public Duration getLeaseDuration() { return leaseDuration; }
    public void setLeaseDuration(Duration leaseDuration) { this.leaseDuration = leaseDuration; }
    public Duration getInitialBackoff() { return initialBackoff; }
    public void setInitialBackoff(Duration initialBackoff) { this.initialBackoff = initialBackoff; }
    public Duration getMaxBackoff() { return maxBackoff; }
    public void setMaxBackoff(Duration maxBackoff) { this.maxBackoff = maxBackoff; }
    public Duration getPublishTimeout() { return publishTimeout; }
    public void setPublishTimeout(Duration publishTimeout) { this.publishTimeout = publishTimeout; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public int getMaxConcurrentPublishes() { return maxConcurrentPublishes; }
    public void setMaxConcurrentPublishes(int maxConcurrentPublishes) { this.maxConcurrentPublishes = maxConcurrentPublishes; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }

    @AssertTrue(message = "audit outbox durations must be positive, bounded, and lease > publish timeout")
    public boolean areDurationsValid() {
        return positive(pollDelay) && positive(leaseDuration) && positive(initialBackoff)
                && positive(maxBackoff) && positive(publishTimeout)
                && pollDelay.compareTo(Duration.ofMinutes(1)) <= 0
                && leaseDuration.compareTo(Duration.ofMinutes(5)) <= 0
                && publishTimeout.compareTo(Duration.ofSeconds(30)) <= 0
                && maxBackoff.compareTo(Duration.ofHours(1)) <= 0
                && maxBackoff.compareTo(initialBackoff) >= 0
                && leaseDuration.compareTo(publishTimeout) > 0;
    }

    private static boolean positive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }
}
