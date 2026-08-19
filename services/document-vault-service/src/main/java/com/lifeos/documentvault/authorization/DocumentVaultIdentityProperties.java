package com.lifeos.documentvault.authorization;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Identity validation connection settings. The V2 document action descriptors are registered in
 * Identity; this service still fails closed when its workload credential is absent or mismatched.
 */
@ConfigurationProperties(prefix = "identity")
@Validated
public class DocumentVaultIdentityProperties {

    @NotBlank(message = "baseUrl must be configured")
    private String baseUrl = "http://localhost:8081";

    @NotBlank(message = "workloadIdentity must be configured")
    private String workloadIdentity = "document-vault-service";

    @NotBlank(message = "workloadToken must be configured")
    private String workloadToken;

    @NotBlank(message = "expectedPolicyVersion must be configured")
    private String expectedPolicyVersion = "v2";

    @NotNull(message = "connectTimeout must be configured")
    private Duration connectTimeout = Duration.ofSeconds(2);

    @NotNull(message = "readTimeout must be configured")
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
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    @AssertTrue(message = "identity timeouts must be positive")
    public boolean areTimeoutsPositive() {
        return connectTimeout != null
                && readTimeout != null
                && !connectTimeout.isNegative()
                && !connectTimeout.isZero()
                && !readTimeout.isNegative()
                && !readTimeout.isZero();
    }

    private static boolean isLoopbackHost(String host) {
        if ("localhost".equalsIgnoreCase(host)) {
            return true;
        }
        try {
            return InetAddress.getByName(host.replace("[", "").replace("]", "")).isLoopbackAddress();
        } catch (UnknownHostException ignored) {
            return false;
        }
    }
}
