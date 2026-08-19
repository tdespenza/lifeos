package com.lifeos.notification.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Base64;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Bounded notification runtime settings. Sensitive values are never exposed through this type. */
@ConfigurationProperties("notification")
@Validated
public class NotificationProperties {

    @NotBlank
    private String endpointEncryptionKey;

    @NotBlank
    private String idempotencySecret;

    @Valid
    @NotNull
    private Delivery delivery = new Delivery();

    @Valid
    @NotNull
    private Outbox outbox = new Outbox();

    @Valid
    @NotNull
    private Stream stream = new Stream();

    @Valid
    @NotNull
    private Kafka kafka = new Kafka();

    /** Bounds the Tomcat connection, keep-alive, and request-body upload phases. */
    @NotNull
    private Duration inboundRequestTimeout = Duration.ofSeconds(30);

    public String getEndpointEncryptionKey() {
        return endpointEncryptionKey;
    }

    public String getIdempotencySecret() {
        return idempotencySecret;
    }

    /** Requires a separate high-entropy secret for keyed endpoint/idempotency digests. */
    public void setIdempotencySecret(String idempotencySecret) {
        if (idempotencySecret == null || idempotencySecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("notification idempotency secret must contain at least 32 UTF-8 bytes");
        }
        this.idempotencySecret = idempotencySecret;
    }

    /**
     * Configures a base64-encoded 256-bit AES-GCM key. A startup validation failure is safer than
     * accepting a short, generated, or source-controlled key.
     */
    public void setEndpointEncryptionKey(String endpointEncryptionKey) {
        if (endpointEncryptionKey == null || endpointEncryptionKey.isBlank()) {
            this.endpointEncryptionKey = endpointEncryptionKey;
            return;
        }
        try {
            if (Base64.getDecoder().decode(endpointEncryptionKey).length != 32) {
                throw new IllegalArgumentException("notification endpoint encryption key must be 32 bytes");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("notification endpoint encryption key must be base64-encoded 32 bytes", exception);
        }
        this.endpointEncryptionKey = endpointEncryptionKey;
    }

    public Delivery getDelivery() {
        return delivery;
    }

    public void setDelivery(Delivery delivery) {
        this.delivery = delivery;
    }

    public Outbox getOutbox() {
        return outbox;
    }

    public void setOutbox(Outbox outbox) {
        this.outbox = outbox;
    }

    public Stream getStream() {
        return stream;
    }

    public void setStream(Stream stream) {
        this.stream = stream;
    }

    public Kafka getKafka() {
        return kafka;
    }

    public void setKafka(Kafka kafka) {
        this.kafka = kafka;
    }

    public Duration getInboundRequestTimeout() {
        return inboundRequestTimeout;
    }

    public void setInboundRequestTimeout(Duration inboundRequestTimeout) {
        this.inboundRequestTimeout = inboundRequestTimeout;
    }

    @jakarta.validation.constraints.AssertTrue(message = "inboundRequestTimeout must be between one millisecond and 60 seconds")
    boolean hasBoundedInboundRequestTimeout() {
        return inboundRequestTimeout != null
                && !inboundRequestTimeout.isZero()
                && !inboundRequestTimeout.isNegative()
                && inboundRequestTimeout.compareTo(Duration.ofMillis(1)) >= 0
                && inboundRequestTimeout.compareTo(Duration.ofSeconds(60)) <= 0;
    }

    public static class Delivery {

        private boolean workerEnabled = true;

        @Min(1)
        @Max(20)
        private int maxAttempts = 5;

        @NotNull
        private Duration initialBackoff = Duration.ofSeconds(1);

        @NotNull
        private Duration maxBackoff = Duration.ofMinutes(5);

        @NotNull
        private Duration providerTimeout = Duration.ofSeconds(5);

        @Min(1)
        @Max(500)
        private int batchSize = 50;

        @NotNull
        private Duration pollDelay = Duration.ofSeconds(1);

        @NotNull
        private Duration leaseDuration = Duration.ofSeconds(30);

        public boolean isWorkerEnabled() {
            return workerEnabled;
        }

        public void setWorkerEnabled(boolean workerEnabled) {
            this.workerEnabled = workerEnabled;
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

        public Duration getProviderTimeout() {
            return providerTimeout;
        }

        public void setProviderTimeout(Duration providerTimeout) {
            this.providerTimeout = providerTimeout;
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

    public static class Outbox {

        private boolean relayEnabled = true;

        @Min(1)
        @Max(500)
        private int batchSize = 100;

        @NotNull
        private Duration pollDelay = Duration.ofSeconds(1);

        @NotNull
        private Duration initialBackoff = Duration.ofSeconds(1);

        @NotNull
        private Duration maxBackoff = Duration.ofMinutes(5);

        @NotNull
        private Duration leaseDuration = Duration.ofSeconds(30);

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

        public Duration getPollDelay() {
            return pollDelay;
        }

        public void setPollDelay(Duration pollDelay) {
            this.pollDelay = pollDelay;
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

        public Duration getLeaseDuration() {
            return leaseDuration;
        }

        public void setLeaseDuration(Duration leaseDuration) {
            this.leaseDuration = leaseDuration;
        }
    }

    public static class Stream {

        @Min(1)
        @Max(10)
        private int maxConnectionsPerAccount = 3;

        @Min(1)
        @Max(1_000)
        private int queueCapacity = 100;

        @Min(1)
        @Max(1_000)
        private int replayLimit = 100;

        @NotNull
        private Duration heartbeatInterval = Duration.ofSeconds(15);

        @NotNull
        private Duration connectionTimeout = Duration.ofMinutes(30);

        public int getMaxConnectionsPerAccount() {
            return maxConnectionsPerAccount;
        }

        public void setMaxConnectionsPerAccount(int maxConnectionsPerAccount) {
            this.maxConnectionsPerAccount = maxConnectionsPerAccount;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

        public int getReplayLimit() {
            return replayLimit;
        }

        public void setReplayLimit(int replayLimit) {
            this.replayLimit = replayLimit;
        }

        public Duration getHeartbeatInterval() {
            return heartbeatInterval;
        }

        public void setHeartbeatInterval(Duration heartbeatInterval) {
            this.heartbeatInterval = heartbeatInterval;
        }

        public Duration getConnectionTimeout() {
            return connectionTimeout;
        }

        public void setConnectionTimeout(Duration connectionTimeout) {
            this.connectionTimeout = connectionTimeout;
        }
    }

    public static class Kafka {

        private boolean consumerEnabled = true;

        private boolean realtimeConsumerEnabled = true;

        @NotBlank
        private String consumerGroup = "notification-service-v1";

        /**
         * Stable, deployment-supplied identity for the local SSE fanout process. It deliberately
         * is not a Kafka group name: the runtime derives a private status-consumer group from it
         * so replicas cannot accidentally load-balance events intended for every local stream hub.
         */
        private String realtimeInstanceId;

        public boolean isConsumerEnabled() {
            return consumerEnabled;
        }

        public void setConsumerEnabled(boolean consumerEnabled) {
            this.consumerEnabled = consumerEnabled;
        }

        public boolean isRealtimeConsumerEnabled() {
            return realtimeConsumerEnabled;
        }

        public void setRealtimeConsumerEnabled(boolean realtimeConsumerEnabled) {
            this.realtimeConsumerEnabled = realtimeConsumerEnabled;
        }

        public String getConsumerGroup() {
            return consumerGroup;
        }

        public void setConsumerGroup(String consumerGroup) {
            this.consumerGroup = consumerGroup;
        }

        public String getRealtimeInstanceId() {
            return realtimeInstanceId;
        }

        public void setRealtimeInstanceId(String realtimeInstanceId) {
            this.realtimeInstanceId = realtimeInstanceId;
        }
    }
}
