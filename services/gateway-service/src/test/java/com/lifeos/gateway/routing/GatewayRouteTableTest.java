package com.lifeos.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifeos.gateway.config.GatewayProperties;
import java.net.URI;
import java.util.List;
import java.util.Set;
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
    void protectsRoutesByDefaultAndAllowsAnExplicitPublicBootstrapRoute() {
        GatewayRouteTable table = new GatewayRouteTable(properties(
                new GatewayProperties.Route("protected", "/api/v1/goals", "https://goals.test"),
                new GatewayProperties.Route("public", "/api/v1/auth", "https://identity.test", false)));

        assertThat(table.resolve("/api/v1/goals")).get()
                .extracting(GatewayRoute::authenticationRequired)
                .isEqualTo(true);
        assertThat(table.resolve("/api/v1/auth/login")).get()
                .extracting(GatewayRoute::authenticationRequired)
                .isEqualTo(false);
        assertThat(table.resolve("/api/v1/goals")).get()
                .satisfies(route -> assertThat(route.requiresAuthentication("GET")).isTrue());
        assertThat(table.resolve("/api/v1/auth/login")).get()
                .satisfies(route -> assertThat(route.requiresAuthentication("POST")).isFalse());
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
    void rejectsMediaUploadVirtualRouteIdCollisionsInEitherDeclarationOrder() {
        assertVirtualRouteIdCollision("media-media-upload", true, false);
    }

    @Test
    void rejectsMediaHlsVirtualRouteIdCollisionsInEitherDeclarationOrder() {
        assertVirtualRouteIdCollision("media-media-hls", false, true);
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

    @Test
    void rejectsUnknownMethodScopedAuthenticationPoliciesDuringValidation() {
        GatewayProperties.Route route = new GatewayProperties.Route(
                "goals", "/api/v1/goals", "https://task-goal.test");
        route.setAuthenticationRequiredMethods(Set.of("GEET"));

        assertThat(route.areAuthenticationRequiredMethodsValid()).isFalse();
    }

    @Test
    void rejectsPublicMethodsThatAreNotInTheProtectedMethodSet() {
        GatewayProperties.Route route = new GatewayProperties.Route(
                "accounts", "/api/v1/accounts", "https://identity.test");
        route.setAuthenticationRequiredMethods(Set.of("GET"));
        route.setAuthenticationPublicPaths(Set.of("/api/v1/accounts"));
        route.setAuthenticationPublicMethods(Set.of("POST"));

        assertThat(route.areAuthenticationPublicMethodsValid()).isFalse();
    }

    @Test
    void makesOnlyTheExactRegistrationPostPublic() {
        GatewayProperties.Route registration = new GatewayProperties.Route(
                "accounts", "/api/v1/accounts", "https://identity.test");
        registration.setAuthenticationRequiredMethods(
                Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        registration.setAuthenticationPublicPaths(Set.of("/api/v1/accounts"));
        registration.setAuthenticationPublicMethods(Set.of("POST"));
        GatewayRoute route = new GatewayRouteTable(properties(registration))
                .resolve("/api/v1/accounts")
                .orElseThrow();

        assertThat(route.requiresAuthentication("/api/v1/accounts", "POST")).isFalse();
        assertThat(route.requiresAuthentication("/api/v1/accounts/child", "POST")).isTrue();
        assertThat(route.requiresAuthentication("/api/v1/accounts/child", "PUT")).isTrue();
        assertThat(route.requiresAuthentication("/api/v1/accounts/child", "PATCH")).isTrue();
        assertThat(route.requiresAuthentication("/api/v1/accounts/child", "DELETE")).isTrue();
    }

    @Test
    void acceptsOnlyReviewedStreamingRoutePolicies() {
        assertThat(new GatewayRoute(
                        "notifications",
                        GatewayRoute.NOTIFICATION_STREAM_PATH,
                        URI.create("https://notifications.test"),
                        true,
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        true,
                        false,
                        false,
                        false)
                .isExactStreamingRequest(GatewayRoute.NOTIFICATION_STREAM_PATH, "GET"))
                .isTrue();
        assertThat(new GatewayRoute(
                        "notifications",
                        GatewayRoute.NOTIFICATION_STREAM_PATH,
                        URI.create("https://notifications.test"),
                        true,
                        Set.of("GET"),
                        Set.of(),
                        Set.of(),
                        true,
                        false,
                        false,
                        false)
                .isExactStreamingRequest(GatewayRoute.NOTIFICATION_STREAM_PATH, "GET"))
                .isTrue();

        assertInvalidStreamingRoute("/api/v1/notifications", true, Set.of("GET"), Set.of(), Set.of());
        assertInvalidStreamingRoute(GatewayRoute.NOTIFICATION_STREAM_PATH, false, Set.of("GET"), Set.of(), Set.of());
        assertInvalidStreamingRoute(
                GatewayRoute.NOTIFICATION_STREAM_PATH, true, Set.of("POST"), Set.of(), Set.of());
        assertInvalidStreamingRoute(
                GatewayRoute.NOTIFICATION_STREAM_PATH,
                true,
                Set.of("GET"),
                Set.of(GatewayRoute.NOTIFICATION_STREAM_PATH),
                Set.of());
        assertInvalidStreamingRoute(
                GatewayRoute.NOTIFICATION_STREAM_PATH, true, Set.of("GET"), Set.of(), Set.of("GET"));
    }

    @Test
    void rejectsInvalidUploadAndHlsStreamingPolicies() {
        assertThatThrownBy(() -> new GatewayRoute(
                        "documents",
                        GatewayRoute.DOCUMENT_UPLOAD_PATH,
                        URI.create("https://documents.test"),
                        true,
                        Set.of("POST"),
                        Set.of(),
                        Set.of(),
                        false,
                        true,
                        false,
                        false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GatewayRoute(
                        "media",
                        GatewayRoute.MEDIA_ASSETS_PATH_PREFIX,
                        URI.create("https://media.test"),
                        true,
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        true,
                        false,
                        true,
                        false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GatewayRoute(
                        "media",
                        GatewayRoute.MEDIA_ASSETS_PATH_PREFIX,
                        URI.create("https://media.test"),
                        true,
                        Set.of(),
                        Set.of(GatewayRoute.MEDIA_ASSETS_PATH_PREFIX),
                        Set.of(),
                        false,
                        false,
                        false,
                        true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void classifiesOnlyExactDocumentUploadsForStreaming() {
        GatewayRoute route = documentUploadRoute();

        assertThat(route.isExactDocumentUploadRequest(GatewayRoute.DOCUMENT_UPLOAD_PATH, "POST"))
                .isTrue();
        assertThat(route.isExactDocumentUploadRequest(GatewayRoute.DOCUMENT_UPLOAD_PATH + "/child", "POST"))
                .isFalse();
        assertThat(route.isExactDocumentUploadRequest(GatewayRoute.DOCUMENT_UPLOAD_PATH, "PUT"))
                .isFalse();
    }

    @Test
    void classifiesOnlyCanonicalMediaSourceUploadsForStreaming() {
        GatewayRoute route = mediaUploadRoute();
        String asset = GatewayRoute.MEDIA_ASSETS_PATH_PREFIX + "/123e4567-e89b-12d3-a456-426614174000";

        assertThat(route.isExactMediaUploadRequest(asset + "/source", "PUT")).isTrue();
        assertThat(route.isExactMediaUploadRequest(asset + "/source", "POST")).isFalse();
        assertThat(route.isExactMediaUploadRequest(asset + "/source/child", "PUT")).isFalse();
        assertThat(route.isExactMediaUploadRequest(
                        GatewayRoute.MEDIA_ASSETS_PATH_PREFIX + "/not-a-canonical-uuid/source", "PUT"))
                .isFalse();
    }

    @Test
    void classifiesOnlyReviewedMediaHlsResponsesForStreaming() {
        GatewayRoute route = mediaHlsRoute();
        String asset = GatewayRoute.MEDIA_ASSETS_PATH_PREFIX + "/123e4567-e89b-12d3-a456-426614174000";

        assertThat(route.isExactMediaHlsRequest(asset + "/hls/master.m3u8", "GET")).isTrue();
        assertThat(route.isExactMediaHlsRequest(asset + "/hls/segments/part-001.m4s", "GET")).isTrue();
        assertThat(route.isExactMediaHlsRequest(asset + "/hls/segments/part-001.ts", "GET")).isTrue();
        assertThat(route.isExactMediaHlsRequest(asset + "/hls/segments/nested/part.m4s", "GET"))
                .isFalse();
        assertThat(route.isExactMediaHlsRequest(asset + "/hls/segments/../part.m4s", "GET"))
                .isFalse();
        assertThat(route.isExactMediaHlsRequest(asset + "/hls/master.m3u8", "POST")).isFalse();
        assertThat(route.isExactMediaHlsRequest(
                        GatewayRoute.MEDIA_ASSETS_PATH_PREFIX
                                + "/not-a-canonical-uuid/hls/master.m3u8",
                        "GET"))
                .isFalse();
    }

    @Test
    void validatesVersionedPrefixesAndLoopbackUpstreams() {
        assertThat(GatewayRoute.isValidPathPrefix("/")).isTrue();
        assertThat(GatewayRoute.isValidPathPrefix("/api/v1/goals")).isTrue();
        assertThat(GatewayRoute.isValidPathPrefix("/api/v12/goals/active")).isTrue();
        assertThat(GatewayRoute.isValidPathPrefix(null)).isFalse();
        assertThat(GatewayRoute.isValidPathPrefix(" ")).isFalse();
        assertThat(GatewayRoute.isValidPathPrefix("api/v1/goals")).isFalse();
        assertThat(GatewayRoute.isValidPathPrefix("/api/v0/goals")).isFalse();
        assertThat(GatewayRoute.isValidPathPrefix("/api/v1/")).isFalse();
        assertThat(GatewayRoute.isValidPathPrefix("/api/v1/goals/")).isFalse();
        assertThat(GatewayRoute.isValidPathPrefix("/api/v1/goals//active")).isFalse();
        assertThat(GatewayRoute.isValidPathPrefix("/api/v1/goals?draft")).isFalse();
        assertThat(GatewayRoute.isValidPathPrefix("/api/v1/goals#section")).isFalse();
        assertThat(GatewayRoute.isValidPathPrefix("/api/v1/*")).isFalse();
        assertThat(GatewayRoute.isValidPathPrefix("/api/v1/{goal}")).isFalse();

        assertThat(GatewayRoute.isValidUpstream("https://gateway.test")).isTrue();
        assertThat(GatewayRoute.isValidUpstream("http://localhost:8080")).isTrue();
        assertThat(GatewayRoute.isValidUpstream("http://127.0.0.1:8080")).isTrue();
        assertThat(GatewayRoute.isValidUpstream("http://[::1]:8080")).isTrue();
        assertThat(GatewayRoute.isValidUpstream("http://gateway.test")).isFalse();
    }

    private static void assertInvalidStreamingRoute(
            String pathPrefix,
            boolean authenticationRequired,
            Set<String> authenticationRequiredMethods,
            Set<String> authenticationPublicPaths,
            Set<String> authenticationPublicMethods) {
        assertThatThrownBy(() -> new GatewayRoute(
                        "notifications",
                        pathPrefix,
                        URI.create("https://notifications.test"),
                        authenticationRequired,
                        authenticationRequiredMethods,
                        authenticationPublicPaths,
                        authenticationPublicMethods,
                        true,
                        false,
                        false,
                        false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static GatewayRoute documentUploadRoute() {
        return new GatewayRoute(
                "documents",
                GatewayRoute.DOCUMENT_UPLOAD_PATH,
                URI.create("https://documents.test"),
                true,
                Set.of(),
                Set.of(),
                Set.of(),
                false,
                true,
                false,
                false);
    }

    private static GatewayRoute mediaUploadRoute() {
        return new GatewayRoute(
                "media",
                GatewayRoute.MEDIA_ASSETS_PATH_PREFIX,
                URI.create("https://media.test"),
                true,
                Set.of(),
                Set.of(),
                Set.of(),
                false,
                false,
                true,
                false);
    }

    private static GatewayRoute mediaHlsRoute() {
        return new GatewayRoute(
                "media",
                GatewayRoute.MEDIA_ASSETS_PATH_PREFIX,
                URI.create("https://media.test"),
                true,
                Set.of(),
                Set.of(),
                Set.of(),
                false,
                false,
                false,
                true);
    }

    private static void assertVirtualRouteIdCollision(
            String collidingId, boolean mediaUploadStreaming, boolean mediaHlsStreaming) {
        GatewayProperties.Route streaming = new GatewayProperties.Route(
                "media", GatewayRoute.MEDIA_ASSETS_PATH_PREFIX, "https://media.test");
        streaming.setMediaUploadStreaming(mediaUploadStreaming);
        streaming.setMediaHlsStreaming(mediaHlsStreaming);
        GatewayProperties.Route colliding = new GatewayProperties.Route(
                collidingId, "/api/v1/other", "https://other.test");

        assertThatThrownBy(() -> new GatewayRouteTable(properties(streaming, colliding)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("route id");
        assertThatThrownBy(() -> new GatewayRouteTable(properties(colliding, streaming)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("route id");
    }

    private static GatewayProperties properties(GatewayProperties.Route... routes) {
        GatewayProperties properties = new GatewayProperties();
        properties.setRoutes(List.of(routes));
        return properties;
    }
}
