package com.lifeos.notification.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Fail-closed identity validation dependency configuration for notification read APIs. */
@ConfigurationProperties("identity")
@Validated
public class NotificationIdentityProperties {

    @NotNull
    private URI baseUrl;

    @NotBlank
    private String workloadIdentity;

    @NotBlank
    private String workloadToken;

    @NotNull
    private Duration connectTimeout = Duration.ofSeconds(2);

    @NotNull
    private Duration readTimeout = Duration.ofSeconds(3);

    public URI getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(URI baseUrl) {
        if (baseUrl == null || !baseUrl.isAbsolute() || baseUrl.getRawUserInfo() != null
                || baseUrl.getRawQuery() != null || baseUrl.getRawFragment() != null
                || baseUrl.getRawPath() == null || !baseUrl.getRawPath().isEmpty()) {
            throw new IllegalArgumentException("identity baseUrl must be an absolute origin");
        }
        if (!"https".equalsIgnoreCase(baseUrl.getScheme()) && !isLoopback(baseUrl.getHost())) {
            throw new IllegalArgumentException("identity baseUrl must use HTTPS unless its host is loopback");
        }
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

    private static boolean isLoopback(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        if ("localhost".equalsIgnoreCase(host)) {
            return true;
        }
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (Exception ignored) {
            return false;
        }
    }
}
