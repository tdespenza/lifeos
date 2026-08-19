package com.lifeos.assistant.config;

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

/** Identity validation endpoint and workload credentials for this isolated service. */
@ConfigurationProperties(prefix = "identity")
@Validated
public class AssistantIdentityProperties {

    @NotBlank(message = "baseUrl must be configured")
    private String baseUrl = "http://localhost:8081";

    @NotBlank(message = "workloadIdentity must be configured")
    private String workloadIdentity = "ai-assistant-service";

    @NotBlank(message = "workloadToken must be configured")
    private String workloadToken;

    @NotNull(message = "connectTimeout must be configured")
    private Duration connectTimeout = Duration.ofSeconds(2);

    @NotNull(message = "readTimeout must be configured")
    private Duration readTimeout = Duration.ofSeconds(3);

    @Min(value = 1, message = "maxConcurrentRequests must be positive")
    @Max(value = 256, message = "maxConcurrentRequests must be bounded")
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
        String normalized = stripIpv6Brackets(host);
        if ("localhost".equalsIgnoreCase(normalized)) {
            return true;
        }
        if (!isIpLiteral(normalized)) {
            return false;
        }
        try {
            return InetAddress.getByName(normalized).isLoopbackAddress();
        } catch (UnknownHostException ignored) {
            return false;
        }
    }

    private static String stripIpv6Brackets(String host) {
        if (host.length() > 2 && host.charAt(0) == '[' && host.charAt(host.length() - 1) == ']') {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    private static boolean isIpLiteral(String host) {
        return host.matches("[0-9.]+") || host.contains(":");
    }
}
