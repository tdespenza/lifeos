package com.lifeos.gateway.routing;

import com.lifeos.gateway.config.GatewayProperties;
import java.net.URI;

/**
 * Immutable route representation used after configuration binding and validation.
 */
public record GatewayRoute(String id, String pathPrefix, URI upstream) {

    /**
     * Creates an immutable route from deployment configuration.
     *
     * @param route configured route
     * @return immutable route
     */
    public static GatewayRoute from(GatewayProperties.Route route) {
        return new GatewayRoute(
                route.getId(), normalizePathPrefix(route.getPathPrefix()), normalizeUpstream(route.getUpstream()));
    }

    /**
     * Checks the complete public path-prefix rule used by configuration binding and route
     * materialization.
     *
     * @param value configured path prefix
     * @return whether the value is a safe absolute path prefix
     */
    public static boolean isValidPathPrefix(String value) {
        return value != null
                && !value.isBlank()
                && value.startsWith("/")
                && !value.contains("//")
                && !value.contains("?")
                && !value.contains("#")
                && !value.contains("*")
                && !value.contains("{")
                && !value.contains("}")
                && (value.length() == 1 || !value.endsWith("/"));
    }

    /**
     * Checks the complete fixed HTTP(S) origin rule used by configuration binding and route
     * materialization.
     *
     * @param value configured upstream origin
     * @return whether the value is a safe absolute HTTP(S) origin
     */
    public static boolean isValidUpstream(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            return uri.isAbsolute()
                    && uri.getHost() != null
                    && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getUserInfo() == null
                    && uri.getQuery() == null
                    && uri.getFragment() == null
                    && (uri.getPath() == null || uri.getPath().isEmpty() || "/".equals(uri.getPath()));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String normalizePathPrefix(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("route pathPrefix must not be blank");
        }
        if (!isValidPathPrefix(value)) {
            throw new IllegalArgumentException("route pathPrefix is not a valid public path");
        }
        return value;
    }

    private static URI normalizeUpstream(String value) {
        if (!isValidUpstream(value)) {
            throw new IllegalArgumentException("route upstream must be an HTTP(S) origin");
        }
        return URI.create(value);
    }
}
