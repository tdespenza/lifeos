package com.lifeos.documentvault.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Bounded producer-outbox relay settings owned by the deployment. */
@ConfigurationProperties(prefix = "document-vault.proof-outbox")
@Validated
public class DocumentProofOutboxProperties {

    private boolean relayEnabled;

    @Min(1)
    @Max(200)
    private int batchSize = 50;

    @Min(1)
    @Max(16)
    private int maxConcurrentPublishes = 4;

    @Min(1)
    @Max(100)
    private int maxAttempts = 10;

    @NotNull
    private Duration pollDelay = Duration.ofSeconds(1);

    @NotNull
    private Duration leaseDuration = Duration.ofSeconds(30);

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

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
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

    @AssertTrue(message = "proof outbox timing must be positive, bounded, and ordered")
    public boolean isTimingValid() {
        return boundedPositive(pollDelay)
                && boundedPositive(leaseDuration)
                && boundedPositive(initialBackoff)
                && boundedPositive(maxBackoff)
                && maxBackoff.compareTo(initialBackoff) >= 0;
    }

    private static boolean boundedPositive(Duration value) {
        return value != null && value.compareTo(Duration.ZERO) > 0
                && value.compareTo(Duration.ofHours(1)) <= 0;
    }
}
