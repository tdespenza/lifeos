package com.lifeos.media.authorization;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Bounded workload-authenticated Identity client configuration. */
@ConfigurationProperties(prefix = "identity")
@Validated
public class MediaIdentityProperties {

    @NotBlank
    private String baseUrl;

    @NotBlank
    private String workloadIdentity;

    @NotBlank
    private String workloadToken;

    @NotBlank
    private String expectedPolicyVersion;

    @NotNull
    private Duration connectTimeout = Duration.ofSeconds(2);

    @NotNull
    private Duration readTimeout = Duration.ofSeconds(3);

    @Min(1)
    @Max(128)
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

    @AssertTrue(message = "Identity timeouts must be positive and no greater than ten seconds")
    public boolean isTimeoutsValid() {
        return validTimeout(connectTimeout) && validTimeout(readTimeout);
    }

    private static boolean validTimeout(Duration value) {
        return value != null
                && value.compareTo(Duration.ZERO) > 0
                && value.compareTo(Duration.ofSeconds(10)) <= 0;
    }
}
