package com.lifeos.assistant.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.AssertTrue;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Bounded, opt-in connection settings for the confirmed TaskGoal tool. */
@ConfigurationProperties(prefix = "assistant.task-goal-tool")
@Validated
public class AssistantTaskGoalToolProperties {

    private String baseUrl = "http://localhost:8082";
    private String workloadIdentity = "ai-assistant-service";
    private String workloadToken = "";

    @NotNull
    private Duration connectTimeout = Duration.ofSeconds(2);

    @NotNull
    private Duration readTimeout = Duration.ofSeconds(5);

    @Min(1)
    @Max(32)
    private int maxConcurrentRequests = 8;

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

    public int getMaxConcurrentRequests() {
        return maxConcurrentRequests;
    }

    public void setMaxConcurrentRequests(int maxConcurrentRequests) {
        this.maxConcurrentRequests = maxConcurrentRequests;
    }

    public boolean configured() {
        return baseUrl != null
                && !baseUrl.isBlank()
                && workloadIdentity != null
                && !workloadIdentity.isBlank()
                && workloadToken != null
                && !workloadToken.isBlank();
    }

    @AssertTrue(message = "baseUrl must be an absolute HTTPS URL unless its host is loopback")
    public boolean isBaseUrlValid() {
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(baseUrl);
            if (!uri.isAbsolute() || uri.getHost() == null) {
                return false;
            }
            return "https".equalsIgnoreCase(uri.getScheme())
                    || ("http".equalsIgnoreCase(uri.getScheme()) && isLoopbackHost(uri.getHost()));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    @AssertTrue(message = "connectTimeout must be positive")
    public boolean isConnectTimeoutPositive() {
        return isPositive(connectTimeout);
    }

    @AssertTrue(message = "readTimeout must be positive")
    public boolean isReadTimeoutPositive() {
        return isPositive(readTimeout);
    }

    private static boolean isPositive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }

    private static boolean isLoopbackHost(String host) {
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "[::1]".equals(host)
                || "::1".equals(host);
    }
}
