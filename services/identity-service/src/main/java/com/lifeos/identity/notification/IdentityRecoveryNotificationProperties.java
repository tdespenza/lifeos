package com.lifeos.identity.notification;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Bounded, deployment-owned recovery notification outbox and relay settings. */
@ConfigurationProperties(prefix = "identity.recovery-notification")
@Validated
public class IdentityRecoveryNotificationProperties {

    private boolean relayEnabled = true;

    @NotBlank(message = "recovery notification topic must be configured")
    private String topic = "lifeos.notification.requested.v2";

    @Min(1)
    @Max(500)
    private int batchSize = 100;

    @Min(1)
    @Max(64)
    private int maxConcurrentPublishes = 8;

    @NotNull
    private Duration pollDelay = Duration.ofSeconds(1);

    @NotNull
    private Duration leaseDuration = Duration.ofSeconds(30);

    @Min(1)
    @Max(20)
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

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
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

    @AssertTrue(message = "recovery notification relay timing must be positive, bounded, and ordered")
    public boolean isTimingValid() {
        return positive(pollDelay)
                && positive(leaseDuration)
                && positive(initialBackoff)
                && positive(maxBackoff)
                && maxBackoff.compareTo(initialBackoff) >= 0
                && initialBackoff.compareTo(Duration.ofHours(1)) <= 0
                && maxBackoff.compareTo(Duration.ofDays(1)) <= 0
                && leaseDuration.compareTo(Duration.ofMinutes(30)) <= 0
                && pollDelay.compareTo(Duration.ofMinutes(10)) <= 0;
    }

    private static boolean positive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }
}
