package com.lifeos.calendar.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Fail-closed workload client configuration for Identity validation and authorization decisions. */
@ConfigurationProperties(prefix = "identity")
@Validated
public class CalendarIdentityProperties {

    @NotBlank
    private String baseUrl = "http://localhost:8081";

    @NotBlank
    private String workloadIdentity = "calendar-service";

    @NotBlank
    private String workloadToken;

    @NotBlank
    private String expectedPolicyVersion = "v1";

    @NotNull
    private Duration connectTimeout = Duration.ofSeconds(2);

    @NotNull
    private Duration readTimeout = Duration.ofSeconds(3);

    @Min(1)
    @Max(512)
    private int maxConcurrentRequests = 32;

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

    public String getExpectedPolicyVersion() {
        return expectedPolicyVersion;
    }

    public void setExpectedPolicyVersion(String expectedPolicyVersion) {
        this.expectedPolicyVersion = expectedPolicyVersion;
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
}
