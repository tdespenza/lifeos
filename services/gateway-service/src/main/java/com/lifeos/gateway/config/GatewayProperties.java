package com.lifeos.gateway.config;

import com.lifeos.gateway.routing.GatewayRoute;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Deployment-owned gateway route and resource-bound configuration.
 *
 * <p>Routes are intentionally a finite allow-list. The gateway never derives an upstream from a
 * request path or a caller-controlled header, which prevents the proxy from becoming an SSRF
 * primitive.
 */
@ConfigurationProperties(prefix = "gateway")
@Validated
public class GatewayProperties {

    @Valid
    @NotEmpty(message = "at least one gateway route must be configured")
    private List<Route> routes = new ArrayList<>();

    @Min(value = 1, message = "maxRequestBodyBytes must be positive")
    private long maxRequestBodyBytes = 1_048_576L;

    @Min(value = 1, message = "maxResponseBodyBytes must be positive")
    private long maxResponseBodyBytes = 10_485_760L;

    @NotNull(message = "connectTimeout must be configured")
    private Duration connectTimeout = Duration.ofSeconds(2);

    @NotNull(message = "readTimeout must be configured")
    private Duration readTimeout = Duration.ofSeconds(5);

    /**
     * Returns the configured public route allow-list.
     *
     * @return configured routes
     */
    public List<Route> getRoutes() {
        return List.copyOf(routes);
    }

    /**
     * Replaces the route allow-list during configuration binding.
     *
     * @param routes configured routes
     */
    public void setRoutes(List<Route> routes) {
        this.routes = routes == null ? new ArrayList<>() : new ArrayList<>(routes);
    }

    /**
     * Returns the maximum accepted inbound body size.
     *
     * @return request body limit in bytes
     */
    public long getMaxRequestBodyBytes() {
        return maxRequestBodyBytes;
    }

    /**
     * Sets the maximum accepted inbound body size.
     *
     * @param maxRequestBodyBytes request body limit in bytes
     */
    public void setMaxRequestBodyBytes(long maxRequestBodyBytes) {
        this.maxRequestBodyBytes = maxRequestBodyBytes;
    }

    /**
     * Returns the maximum buffered upstream response size.
     *
     * @return response body limit in bytes
     */
    public long getMaxResponseBodyBytes() {
        return maxResponseBodyBytes;
    }

    /**
     * Sets the maximum buffered upstream response size.
     *
     * @param maxResponseBodyBytes response body limit in bytes
     */
    public void setMaxResponseBodyBytes(long maxResponseBodyBytes) {
        this.maxResponseBodyBytes = maxResponseBodyBytes;
    }

    /**
     * Returns the outbound connection timeout.
     *
     * @return connection timeout
     */
    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    /**
     * Sets the outbound connection timeout.
     *
     * @param connectTimeout connection timeout
     */
    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    /**
     * Returns the outbound response-read timeout.
     *
     * @return read timeout
     */
    public Duration getReadTimeout() {
        return readTimeout;
    }

    /**
     * Sets the outbound response-read timeout.
     *
     * @param readTimeout read timeout
     */
    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    /**
     * Validates timeout values before constructing the HTTP client.
     *
     * @return {@code true} when both timeouts are bounded and positive
     */
    @AssertTrue(message = "gateway timeouts must be positive and no greater than 60 seconds")
    public boolean isTimeoutsValid() {
        return isBoundedPositive(connectTimeout) && isBoundedPositive(readTimeout);
    }

    private static boolean isBoundedPositive(Duration duration) {
        return duration != null
                && !duration.isZero()
                && !duration.isNegative()
                && duration.compareTo(Duration.ofSeconds(60)) <= 0;
    }

    /**
     * One public path prefix and its fixed upstream origin.
     */
    public static class Route {

        @NotBlank(message = "route id must be configured")
        private String id;

        @NotBlank(message = "route pathPrefix must be configured")
        private String pathPrefix;

        @NotBlank(message = "route upstream must be configured")
        private String upstream;

        /**
         * Creates an empty route for Spring configuration binding.
         */
        public Route() {
        }

        /**
         * Creates a route definition for code and tests.
         *
         * @param id route identifier
         * @param pathPrefix public path prefix
         * @param upstream fixed upstream origin
         */
        public Route(String id, String pathPrefix, String upstream) {
            this.id = id;
            this.pathPrefix = pathPrefix;
            this.upstream = upstream;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getPathPrefix() {
            return pathPrefix;
        }

        public void setPathPrefix(String pathPrefix) {
            this.pathPrefix = pathPrefix;
        }

        public String getUpstream() {
            return upstream;
        }

        public void setUpstream(String upstream) {
            this.upstream = upstream;
        }

        /**
         * Rejects wildcard, query-bearing, or non-path prefixes so route matching remains
         * deterministic and cannot be expanded by configuration typos.
         *
         * @return {@code true} when the path prefix is a valid public prefix
         */
        @AssertTrue(message = "route pathPrefix must be an absolute path without wildcards or a trailing slash")
        public boolean isPathPrefixValid() {
            return GatewayRoute.isValidPathPrefix(pathPrefix);
        }

        /**
         * Rejects userinfo, query, fragments, non-HTTP schemes, and base paths. A route upstream is
         * an origin; the incoming public path is appended unchanged.
         *
         * @return {@code true} when the upstream is a safe absolute HTTP(S) origin
         */
        @AssertTrue(message = "route upstream must be an absolute HTTP(S) origin without userinfo, query, or fragment")
        public boolean isUpstreamValid() {
            return GatewayRoute.isValidUpstream(upstream);
        }
    }
}
