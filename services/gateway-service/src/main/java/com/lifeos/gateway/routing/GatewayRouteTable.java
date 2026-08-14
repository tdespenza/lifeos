package com.lifeos.gateway.routing;

import com.lifeos.gateway.config.GatewayProperties;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Finite, immutable public route table.
 *
 * <p>Resolution checks only path-segment prefixes against a hash map. It never performs a remote
 * lookup and never treats arbitrary request text as an upstream, keeping route selection bounded
 * by the request path length and configuration size.
 */
public class GatewayRouteTable {

    private final Map<String, GatewayRoute> routesByPrefix;

    /**
     * Builds an immutable route table and rejects duplicate identifiers or public prefixes.
     *
     * @param properties gateway configuration
     */
    public GatewayRouteTable(GatewayProperties properties) {
        Map<String, GatewayRoute> routes = new HashMap<>();
        Set<String> routeIds = new HashSet<>();
        for (GatewayProperties.Route configuredRoute : properties.getRoutes()) {
            GatewayRoute route = GatewayRoute.from(configuredRoute);
            if (!routeIds.add(route.id())) {
                throw new IllegalStateException("duplicate gateway route id");
            }
            if (routes.putIfAbsent(route.pathPrefix(), route) != null) {
                throw new IllegalStateException("duplicate gateway route path prefix");
            }
        }
        if (routes.isEmpty()) {
            throw new IllegalStateException("gateway route table must not be empty");
        }
        this.routesByPrefix = Map.copyOf(routes);
    }

    /**
     * Resolves the longest configured path-segment prefix.
     *
     * @param requestPath raw request path
     * @return matching route, or empty for an unknown public route
     */
    public Optional<GatewayRoute> resolve(String requestPath) {
        if (requestPath == null || requestPath.isBlank() || !requestPath.startsWith("/")) {
            return Optional.empty();
        }

        int end = requestPath.length();
        while (end > 0) {
            GatewayRoute route = routesByPrefix.get(requestPath.substring(0, end));
            if (route != null) {
                return Optional.of(route);
            }
            int slash = requestPath.lastIndexOf('/', end - 1);
            if (slash < 1) {
                break;
            }
            end = slash;
        }
        return Optional.empty();
    }
}
