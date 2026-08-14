package com.lifeos.gateway.routing;

import com.lifeos.gateway.config.GatewayProperties;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Finite, immutable public route table.
 *
 * <p>Resolution checks only path-segment prefixes against a route list ordered from longest to
 * shortest. It never performs a remote lookup or allocates progressively shorter path copies, and
 * never treats arbitrary request text as an upstream.
 */
public class GatewayRouteTable {

    private final List<GatewayRoute> routesByPrefix;

    /**
     * Builds an immutable route table and rejects duplicate identifiers or public prefixes.
     *
     * @param properties gateway configuration
     */
    public GatewayRouteTable(GatewayProperties properties) {
        List<GatewayRoute> routes = new ArrayList<>();
        Set<String> routeIds = new HashSet<>();
        Set<String> pathPrefixes = new HashSet<>();
        for (GatewayProperties.Route configuredRoute : properties.getRoutes()) {
            GatewayRoute route = GatewayRoute.from(configuredRoute);
            if (!routeIds.add(route.id())) {
                throw new IllegalStateException("duplicate gateway route id");
            }
            if (!pathPrefixes.add(route.pathPrefix())) {
                throw new IllegalStateException("duplicate gateway route path prefix");
            }
            routes.add(route);
        }
        if (routes.isEmpty()) {
            throw new IllegalStateException("gateway route table must not be empty");
        }
        routes.sort(Comparator.comparingInt((GatewayRoute route) -> route.pathPrefix().length()).reversed());
        this.routesByPrefix = List.copyOf(routes);
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

        for (GatewayRoute route : routesByPrefix) {
            String prefix = route.pathPrefix();
            if (requestPath.equals(prefix)
                    || "/".equals(prefix)
                    || (requestPath.length() > prefix.length()
                            && requestPath.startsWith(prefix)
                            && requestPath.charAt(prefix.length()) == '/')) {
                return Optional.of(route);
            }
        }
        return Optional.empty();
    }
}
