package com.lifeos.media.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Bounded, opt-in configuration for the explicit Media-to-TaskGoal command boundary. */
@ConfigurationProperties(prefix = "media.task-goal")
@Validated
public class MediaTaskGoalProperties {

    private String baseUrl = "http://localhost:8082";
    private String workloadIdentity = "media-service";
    private String workloadToken = "";

    @NotNull
    private Duration connectTimeout = Duration.ofSeconds(2);

    @NotNull
    private Duration readTimeout = Duration.ofSeconds(5);

    @Min(1)
    @Max(32)
    private int maxConcurrentRequests = 8;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getWorkloadIdentity() { return workloadIdentity; }
    public void setWorkloadIdentity(String workloadIdentity) { this.workloadIdentity = workloadIdentity; }
    public String getWorkloadToken() { return workloadToken; }
    public void setWorkloadToken(String workloadToken) { this.workloadToken = workloadToken; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
    public int getMaxConcurrentRequests() { return maxConcurrentRequests; }
    public void setMaxConcurrentRequests(int maxConcurrentRequests) { this.maxConcurrentRequests = maxConcurrentRequests; }

    public boolean configured() {
        return baseUrl != null && !baseUrl.isBlank()
                && workloadIdentity != null && !workloadIdentity.isBlank()
                && workloadToken != null && !workloadToken.isBlank();
    }

    @AssertTrue(message = "TaskGoal baseUrl must be an absolute HTTPS URL unless loopback")
    public boolean isBaseUrlValid() {
        if (baseUrl == null || baseUrl.isBlank()) return false;
        try {
            URI uri = URI.create(baseUrl);
            if (!uri.isAbsolute() || uri.getHost() == null) return false;
            return "https".equalsIgnoreCase(uri.getScheme())
                    || ("http".equalsIgnoreCase(uri.getScheme()) && isLoopback(uri.getHost()));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    @AssertTrue(message = "TaskGoal timeouts must be positive and no greater than ten seconds")
    public boolean isTimeoutsValid() {
        return positive(connectTimeout) && positive(readTimeout)
                && connectTimeout.compareTo(Duration.ofSeconds(10)) <= 0
                && readTimeout.compareTo(Duration.ofSeconds(10)) <= 0;
    }

    private static boolean positive(Duration value) {
        return value != null && value.compareTo(Duration.ZERO) > 0;
    }

    private static boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                || "::1".equals(host) || "[::1]".equals(host);
    }
}
