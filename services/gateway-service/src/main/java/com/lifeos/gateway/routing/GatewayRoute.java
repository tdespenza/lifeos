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

    private static String normalizePathPrefix(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("route pathPrefix must not be blank");
        }
        if (!value.startsWith("/") || value.contains("//") || value.contains("?") || value.contains("#")
                || value.contains("*") || value.contains("{") || value.contains("}")
                || (value.length() > 1 && value.endsWith("/"))) {
            throw new IllegalArgumentException("route pathPrefix is not a valid public path");
        }
        return value;
    }

    private static URI normalizeUpstream(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("route upstream must not be blank");
        }
        URI uri = URI.create(value);
        if (!uri.isAbsolute() || uri.getHost() == null
                || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))) {
            throw new IllegalArgumentException("route upstream must be an HTTP(S) origin");
        }
        return uri;
    }
}
