package com.lifeos.analytics.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Deployment-owned limits and event projection settings. */
@Validated
@ConfigurationProperties(prefix = "analytics")
public class AnalyticsProperties {

    @NotBlank
    private String gatewayProofSecret;

    @NotBlank
    private String workloadToken;

    private String assistantWorkloadIdentity = "ai-assistant-service";
    private String assistantWorkloadToken = "";

    @Min(1)
    @Max(100_000)
    private int maxSnapshots = 10_000;

    @Min(1)
    @Max(90)
    private int defaultPeriodDays = 30;

    private boolean kafkaEnabled;
    private String kafkaTopic = "lifeos.notification.requested.v2";
    private String kafkaGroup = "analytics-service-v2";
    private Duration projectionTimeout = Duration.ofSeconds(5);

    /** Bounds the Tomcat connection, keep-alive, and request-body upload phases. */
    private Duration inboundRequestTimeout = Duration.ofSeconds(10);

    public String getGatewayProofSecret() {
        return gatewayProofSecret;
    }

    public void setGatewayProofSecret(String gatewayProofSecret) {
        this.gatewayProofSecret = gatewayProofSecret;
    }

    public String getWorkloadToken() {
        return workloadToken;
    }

    public void setWorkloadToken(String workloadToken) {
        this.workloadToken = workloadToken;
    }

    public String getAssistantWorkloadIdentity() {
        return assistantWorkloadIdentity;
    }

    public void setAssistantWorkloadIdentity(String assistantWorkloadIdentity) {
        this.assistantWorkloadIdentity = assistantWorkloadIdentity;
    }

    public String getAssistantWorkloadToken() {
        return assistantWorkloadToken;
    }

    public void setAssistantWorkloadToken(String assistantWorkloadToken) {
        this.assistantWorkloadToken = assistantWorkloadToken;
    }

    public int getMaxSnapshots() {
        return maxSnapshots;
    }

    public void setMaxSnapshots(int maxSnapshots) {
        this.maxSnapshots = maxSnapshots;
    }

    public int getDefaultPeriodDays() {
        return defaultPeriodDays;
    }

    public void setDefaultPeriodDays(int defaultPeriodDays) {
        this.defaultPeriodDays = defaultPeriodDays;
    }

    public boolean isKafkaEnabled() {
        return kafkaEnabled;
    }

    public void setKafkaEnabled(boolean kafkaEnabled) {
        this.kafkaEnabled = kafkaEnabled;
    }

    public String getKafkaTopic() {
        return kafkaTopic;
    }

    public void setKafkaTopic(String kafkaTopic) {
        this.kafkaTopic = kafkaTopic;
    }

    public String getKafkaGroup() {
        return kafkaGroup;
    }

    public void setKafkaGroup(String kafkaGroup) {
        this.kafkaGroup = kafkaGroup;
    }

    public Duration getProjectionTimeout() {
        return projectionTimeout;
    }

    public void setProjectionTimeout(Duration projectionTimeout) {
        this.projectionTimeout = projectionTimeout;
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
}
