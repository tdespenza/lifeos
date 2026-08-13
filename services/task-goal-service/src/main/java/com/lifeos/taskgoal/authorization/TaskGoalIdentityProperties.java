package com.lifeos.taskgoal.authorization;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Externalized identity-authority connection and workload-identity settings. */
@ConfigurationProperties(prefix = "identity")
@Validated
public class TaskGoalIdentityProperties {

    @NotBlank(message = "baseUrl must be configured")
    private String baseUrl = "http://localhost:8081";

    @NotBlank(message = "workloadIdentity must be configured")
    private String workloadIdentity = "task-goal-service";

    @NotBlank(message = "workloadToken must be configured")
    private String workloadToken;

    @NotBlank(message = "expectedPolicyVersion must be configured")
    private String expectedPolicyVersion = "v1";

    @NotNull(message = "connectTimeout must be configured")
    private Duration connectTimeout = Duration.ofSeconds(2);

    @NotNull(message = "readTimeout must be configured")
    private Duration readTimeout = Duration.ofSeconds(3);

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

    /**
     * Requires encrypted transport for every remotely addressed identity authority. Plain HTTP is
     * intentionally limited to literal loopback hosts so local development remains frictionless
     * without allowing workload credentials to traverse a network in clear text.
     */
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
            if ("https".equalsIgnoreCase(uri.getScheme())) {
                return true;
            }
            return "http".equalsIgnoreCase(uri.getScheme()) && isLoopbackHost(uri.getHost());
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static boolean isLoopbackHost(String host) {
        String normalizedHost = stripIpv6Brackets(host);
        if ("localhost".equalsIgnoreCase(normalizedHost)) {
            return true;
        }
        if (!isIpLiteral(normalizedHost)) {
            return false;
        }
        try {
            return InetAddress.getByName(normalizedHost).isLoopbackAddress();
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
        if (host.indexOf(':') >= 0) {
            return host.matches("[0-9A-Fa-f:.]+");
        }
        return host.matches("\\d{1,3}(?:\\.\\d{1,3}){3}");
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
}
