package com.lifeos.gateway.routing;

import com.lifeos.gateway.config.GatewayProperties;
import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * Immutable route representation used after configuration binding and validation.
 */
public record GatewayRoute(
        String id,
        String pathPrefix,
        URI upstream,
        boolean authenticationRequired,
        Set<String> authenticationRequiredMethods,
        Set<String> authenticationPublicPaths,
        Set<String> authenticationPublicMethods) {

    /**
     * Normalizes method policy values and keeps all route policy sets immutable.
     */
    public GatewayRoute {
        authenticationRequiredMethods = normalizeAuthenticationMethods(authenticationRequiredMethods);
        authenticationPublicPaths = authenticationPublicPaths == null
                ? Set.of()
                : Set.copyOf(authenticationPublicPaths);
        authenticationPublicMethods = normalizeAuthenticationMethods(authenticationPublicMethods);
    }

    /**
     * Creates a route without exact public operation exceptions.
     *
     * @param id route identifier
     * @param pathPrefix public path prefix
     * @param upstream fixed upstream origin
     * @param authenticationRequired whether authentication is required
     * @param authenticationRequiredMethods methods protected by this route
     */
    public GatewayRoute(
            String id,
            String pathPrefix,
            URI upstream,
            boolean authenticationRequired,
            Set<String> authenticationRequiredMethods) {
        this(id, pathPrefix, upstream, authenticationRequired, authenticationRequiredMethods, Set.of(), Set.of());
    }

    /**
     * Creates an immutable route from deployment configuration.
     *
     * @param route configured route
     * @return immutable route
     */
    public static GatewayRoute from(GatewayProperties.Route route) {
        return new GatewayRoute(
                route.getId(),
                normalizePathPrefix(route.getPathPrefix()),
                normalizeUpstream(route.getUpstream()),
                route.isAuthenticationRequired(),
                route.getAuthenticationRequiredMethods(),
                route.getAuthenticationPublicPaths(),
                route.getAuthenticationPublicMethods());
    }

    /**
     * Returns whether the supplied method is protected by this route's policy.
     *
     * @param method inbound HTTP method
     * @return whether gateway authentication is required
     */
    public boolean requiresAuthentication(String method) {
        return requiresAuthentication(pathPrefix, method);
    }

    /**
     * Returns whether the supplied request path and method are protected by this route's policy.
     * Public operation exceptions are exact path matches and never apply to descendants.
     *
     * @param requestPath inbound request path
     * @param method inbound HTTP method
     * @return whether gateway authentication is required
     */
    public boolean requiresAuthentication(String requestPath, String method) {
        if (!authenticationRequired) {
            return false;
        }
        String normalizedMethod = method == null ? null : method.toUpperCase(Locale.ROOT);
        if (!authenticationRequiredMethods.isEmpty()
                && (normalizedMethod == null || !authenticationRequiredMethods.contains(normalizedMethod))) {
            return false;
        }
        return !(authenticationPublicPaths.contains(requestPath)
                && normalizedMethod != null
                && authenticationPublicMethods.contains(normalizedMethod));
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

    private static Set<String> normalizeAuthenticationMethods(Set<String> methods) {
        if (methods == null || methods.isEmpty()) {
            return Set.of();
        }
        return methods.stream()
                .map(method -> method.toUpperCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
