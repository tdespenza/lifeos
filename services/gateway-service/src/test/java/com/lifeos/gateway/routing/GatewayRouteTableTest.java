package com.lifeos.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifeos.gateway.config.GatewayProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class GatewayRouteTableTest {

    @Test
    void resolvesConfiguredPathSegmentsWithoutTreatingSimilarPathsAsMatches() {
        GatewayProperties properties = properties(
                new GatewayProperties.Route("goals", "/api/v1/goals", "https://task-goal.test"),
                new GatewayProperties.Route("auth", "/api/v1/auth", "https://identity.test"));
        GatewayRouteTable table = new GatewayRouteTable(properties);

        assertThat(table.resolve("/api/v1/goals/123")).get().extracting(GatewayRoute::id).isEqualTo("goals");
        assertThat(table.resolve("/api/v1/auth/login")).get().extracting(GatewayRoute::id).isEqualTo("auth");
        assertThat(table.resolve("/api/v1/goals-like/123")).isEmpty();
        assertThat(table.resolve("/api/v1/internal/authorization/decisions")).isEmpty();
    }

    @Test
    void rootPrefixMatchesNestedPaths() {
        GatewayRouteTable table = new GatewayRouteTable(properties(
                new GatewayProperties.Route("root", "/", "https://root.test")));

        assertThat(table.resolve("/nested/path")).get().extracting(GatewayRoute::id).isEqualTo("root");
    }

    @Test
    void rejectsLongUnknownPathsWithoutProgressiveSubstringAllocation() {
        GatewayRouteTable table = new GatewayRouteTable(properties(
                new GatewayProperties.Route("goals", "/api/v1/goals", "https://task-goal.test")));
        String longUnknownPath = "/" + "unknown/".repeat(10_000) + "tail";

        assertThat(table.resolve(longUnknownPath)).isEmpty();
    }

    @Test
    void rejectsDuplicatePrefixesBeforeTheGatewayStarts() {
        GatewayProperties properties = properties(
                new GatewayProperties.Route("first", "/api/v1/goals", "https://one.test"),
                new GatewayProperties.Route("second", "/api/v1/goals", "https://two.test"));

        assertThatThrownBy(() -> new GatewayRouteTable(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("duplicate gateway route path prefix");
    }

    @Test
    void rejectsNonHttpOriginsAndWildcardPaths() {
        GatewayProperties.Route unsafeOrigin = new GatewayProperties.Route(
                "unsafe", "/api/v1/unsafe", "file:///etc/passwd");
        GatewayProperties.Route wildcard = new GatewayProperties.Route(
                "wildcard", "/api/v1/**", "https://identity.test");

        assertThatThrownBy(() -> new GatewayRouteTable(properties(unsafeOrigin)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GatewayRouteTable(properties(wildcard)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static GatewayProperties properties(GatewayProperties.Route... routes) {
        GatewayProperties properties = new GatewayProperties();
        properties.setRoutes(List.of(routes));
        return properties;
    }
}
