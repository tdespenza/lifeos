package com.lifeos.gateway.config;

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
 * Externalized connection and workload-identity settings for the identity validation authority.
 *
 * <p>The workload token has no default. A protected gateway must not silently start with an
 * unauthenticated internal adapter configuration.
 */
@ConfigurationProperties(prefix = "gateway.authentication")
@Validated
public class GatewayAuthenticationProperties {

    @NotBlank(message = "identity baseUrl must be configured")
    private String baseUrl = "http://localhost:8081";

    @NotBlank(message = "identity workloadIdentity must be configured")
    private String workloadIdentity = "gateway-service";

    @NotBlank(message = "identity workloadToken must be configured")
    private String workloadToken;

    @NotNull(message = "identity connectTimeout must be configured")
    private Duration connectTimeout = Duration.ofSeconds(2);

    @NotNull(message = "identity readTimeout must be configured")
    private Duration readTimeout = Duration.ofSeconds(3);

    @Min(value = 1, message = "maxConcurrentValidations must be positive")
    @Max(value = 1024, message = "maxConcurrentValidations must not exceed 1024")
    private int maxConcurrentValidations = 64;

    /**
     * Creates the default local identity authority settings.
     */
    public GatewayAuthenticationProperties() {
    }

    /**
     * Returns the identity-service base URL.
     *
     * @return identity base URL
     */
    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * Sets the identity-service base URL.
     *
     * @param baseUrl identity base URL
     */
    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /**
     * Returns the deployment-managed workload identity.
     *
     * @return workload identity
     */
    public String getWorkloadIdentity() {
        return workloadIdentity;
    }

    /**
     * Sets the deployment-managed workload identity.
     *
     * @param workloadIdentity workload identity
     */
    public void setWorkloadIdentity(String workloadIdentity) {
        this.workloadIdentity = workloadIdentity;
    }

    /**
     * Returns the deployment-managed workload credential.
     *
     * @return workload credential
     */
    public String getWorkloadToken() {
        return workloadToken;
    }

    /**
     * Sets the deployment-managed workload credential.
     *
     * @param workloadToken workload credential
     */
    public void setWorkloadToken(String workloadToken) {
        this.workloadToken = workloadToken;
    }

    /**
     * Returns the bounded connection timeout.
     *
     * @return connection timeout
     */
    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    /**
     * Sets the bounded connection timeout.
     *
     * @param connectTimeout connection timeout
     */
    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    /**
     * Returns the bounded response-read timeout.
     *
     * @return response-read timeout
     */
    public Duration getReadTimeout() {
        return readTimeout;
    }

    /**
     * Sets the bounded response-read timeout.
     *
     * @param readTimeout response-read timeout
     */
    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    /**
     * Returns the maximum number of concurrent identity validations admitted by the gateway.
     *
     * @return validation bulkhead capacity
     */
    public int getMaxConcurrentValidations() {
        return maxConcurrentValidations;
    }

    /**
     * Sets the validation bulkhead capacity during configuration binding.
     *
     * @param maxConcurrentValidations maximum concurrent identity validations
     */
    public void setMaxConcurrentValidations(int maxConcurrentValidations) {
        this.maxConcurrentValidations = maxConcurrentValidations;
    }

    /**
     * Requires encrypted transport for remotely addressed identity authorities. Plain HTTP is
     * limited to literal loopback hosts for local development.
     *
     * @return whether the identity URL is safe for workload credentials
     */
    @AssertTrue(message = "identity baseUrl must be an absolute HTTPS URL unless its host is loopback")
    public boolean isBaseUrlValid() {
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(baseUrl);
            if (!uri.isAbsolute()
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
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

    /**
     * Requires both outbound deadlines to be positive and bounded.
     *
     * @return whether the configured timeouts are safe
     */
    @AssertTrue(message = "identity timeouts must be positive and no greater than 60 seconds")
    public boolean isTimeoutsValid() {
        return isBoundedPositive(connectTimeout) && isBoundedPositive(readTimeout);
    }

    private static boolean isBoundedPositive(Duration duration) {
        return duration != null
                && !duration.isZero()
                && !duration.isNegative()
                && duration.compareTo(Duration.ofSeconds(60)) <= 0;
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
        String[] octets = host.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3
                    || (octet.length() > 1 && octet.charAt(0) == '0')) {
                return false;
            }
            int value = 0;
            for (int index = 0; index < octet.length(); index++) {
                char character = octet.charAt(index);
                if (character < '0' || character > '9') {
                    return false;
                }
                value = value * 10 + (character - '0');
            }
            if (value > 255) {
                return false;
            }
        }
        return true;
    }
}
