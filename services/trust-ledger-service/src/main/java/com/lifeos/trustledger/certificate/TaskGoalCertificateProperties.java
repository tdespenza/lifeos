package com.lifeos.trustledger.certificate;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Bounded, fail-closed adapter settings for the Task/Goal completion projection. */
@ConfigurationProperties(prefix = "task-goal")
@Validated
public class TaskGoalCertificateProperties {

    @NotBlank
    private String baseUrl = "http://localhost:8082";

    @NotBlank
    private String workloadIdentity = "trust-ledger-service";

    @NotBlank
    private String workloadToken;

    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration readTimeout = Duration.ofSeconds(3);

    @Min(1)
    @Max(64)
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

    @jakarta.validation.constraints.AssertTrue(message = "task-goal baseUrl must be an http(s) URL")
    public boolean hasSafeBaseUrl() {
        try {
            URI uri = URI.create(baseUrl);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null;
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
