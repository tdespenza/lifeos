package com.lifeos.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifeos.gateway.config.GatewayProperties;
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
    void rejectsRootAndUnversionedPublicRoutePrefixes() {
        assertThat(GatewayRoute.isValidPathPrefix("/api/v1/goals")).isTrue();
        assertThat(GatewayRoute.isValidPathPrefix("/")).isFalse();
        assertThat(GatewayRoute.isValidPathPrefix("/goals")).isFalse();
        assertThat(GatewayRoute.isValidPathPrefix("/api/v0/goals")).isFalse();
        assertThat(GatewayRoute.isValidPathPrefix("/api/v01/goals")).isFalse();
        assertThat(GatewayRoute.isValidPathPrefix("/api/v1")).isFalse();

        assertThatThrownBy(() -> new GatewayRouteTable(properties(
                        new GatewayProperties.Route("root", "/", "https://root.test"))))
                .isInstanceOf(IllegalArgumentException.class);
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
    void identifiesOnlyTheAssistantPrefixForItsDedicatedOutboundPolicy() {
        GatewayRoute assistant = new GatewayRouteTable(properties(
                        new GatewayProperties.Route(
                                "assistant", GatewayRoute.AI_ASSISTANT_PATH_PREFIX, "https://assistant.test")))
                .resolve(GatewayRoute.AI_ASSISTANT_PATH_PREFIX + "/conversations")
                .orElseThrow();
        GatewayRoute other = new GatewayRouteTable(properties(
                        new GatewayProperties.Route("goals", "/api/v1/goals", "https://goals.test")))
                .resolve("/api/v1/goals")
                .orElseThrow();

        assertThat(assistant.isAiAssistantRoute()).isTrue();
        assertThat(assistant.requiresAuthentication("GET")).isTrue();
        assertThat(other.isAiAssistantRoute()).isFalse();
    }

    @Test
    void rejectsAnyPublicOrStreamingExceptionForTheAssistantPrefix() {
        GatewayProperties.Route publicAssistant = new GatewayProperties.Route(
                "assistant", GatewayRoute.AI_ASSISTANT_PATH_PREFIX, "https://assistant.test", false);
        GatewayProperties.Route methodScopedAssistant = new GatewayProperties.Route(
                "assistant", GatewayRoute.AI_ASSISTANT_PATH_PREFIX, "https://assistant.test");
        methodScopedAssistant.setAuthenticationRequiredMethods(Set.of("POST"));

        assertThat(publicAssistant.isAiAssistantRouteConfigurationValid()).isFalse();
        assertThat(methodScopedAssistant.isAiAssistantRouteConfigurationValid()).isFalse();
        assertThatThrownBy(() -> new GatewayRouteTable(properties(publicAssistant)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AI assistant route");
        assertThatThrownBy(() -> new GatewayRouteTable(properties(methodScopedAssistant)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AI assistant route");
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
    void rejectsRealRouteIdsThatCollideWithMediaVirtualResilienceIds() {
        GatewayProperties.Route media = new GatewayProperties.Route(
                "media-assets", GatewayRoute.MEDIA_ASSETS_PATH_PREFIX, "https://media.test");
        media.setMediaUploadStreaming(true);
        media.setMediaHlsStreaming(true);
        GatewayProperties.Route collision = new GatewayProperties.Route(
                "media-assets-media-upload", "/api/v1/media-control", "https://other.test");

        assertThatThrownBy(() -> new GatewayRouteTable(properties(media, collision)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("collides with a reserved virtual route id");
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
    void requiresHttpsForRemoteUpstreamsAndAllowsOnlyLoopbackHttpForLocalDevelopment() {
        assertThat(GatewayRoute.isValidUpstream("https://task-goal.production.example:8443"))
                .isTrue();
        assertThat(GatewayRoute.isValidUpstream("http://localhost:8082")).isTrue();
        assertThat(GatewayRoute.isValidUpstream("http://127.0.0.1:8082")).isTrue();
        assertThat(GatewayRoute.isValidUpstream("http://[::1]:8082")).isTrue();

        assertThat(GatewayRoute.isValidUpstream("http://task-goal.production.example:8082"))
                .isFalse();
        assertThat(GatewayRoute.isValidUpstream("http://192.0.2.10:8082")).isFalse();
        assertThat(GatewayRoute.isValidUpstream("http://127.0.0.01:8082")).isFalse();
        assertThat(GatewayRoute.isValidUpstream("https://user:pass@task-goal.production.example"))
                .isFalse();
        assertThat(GatewayRoute.isValidUpstream("https://task-goal.production.example/base-path"))
                .isFalse();

        GatewayProperties.Route remoteHttp = new GatewayProperties.Route(
                "remote-http", "/api/v1/unsafe", "http://task-goal.production.example:8082");
        assertThatThrownBy(() -> new GatewayRouteTable(properties(remoteHttp)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
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
    void permitsStreamingOnlyForTheExactAuthenticatedNotificationGetRoute() {
        GatewayProperties.Route stream = new GatewayProperties.Route(
                "notification-stream",
                GatewayRoute.NOTIFICATION_STREAM_PATH,
                "https://notification.test");
        stream.setAuthenticationRequiredMethods(Set.of("GET"));
        stream.setStreaming(true);

        GatewayRoute route = new GatewayRouteTable(properties(stream))
                .resolve(GatewayRoute.NOTIFICATION_STREAM_PATH)
                .orElseThrow();

        assertThat(route.streaming()).isTrue();
        assertThat(route.isExactStreamingRequest(GatewayRoute.NOTIFICATION_STREAM_PATH, "GET"))
                .isTrue();
        assertThat(route.isExactStreamingRequest(GatewayRoute.NOTIFICATION_STREAM_PATH + "/child", "GET"))
                .isFalse();
        assertThat(route.isExactStreamingRequest(GatewayRoute.NOTIFICATION_STREAM_PATH, "POST"))
                .isFalse();
    }

    @Test
    void rejectsStreamingConfigurationForAnyOtherPathOrPublicPolicy() {
        GatewayProperties.Route arbitraryPath = new GatewayProperties.Route(
                "unsafe-stream", "/api/v1/goals", "https://task-goal.test");
        arbitraryPath.setAuthenticationRequiredMethods(Set.of("GET"));
        arbitraryPath.setStreaming(true);

        assertThat(arbitraryPath.isStreamingConfigurationValid()).isFalse();
        assertThatThrownBy(() -> new GatewayRouteTable(properties(arbitraryPath)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("streaming routes");

        GatewayProperties.Route publicStream = new GatewayProperties.Route(
                "notification-stream",
                GatewayRoute.NOTIFICATION_STREAM_PATH,
                "https://notification.test");
        publicStream.setAuthenticationRequiredMethods(Set.of("GET"));
        publicStream.setAuthenticationPublicPaths(Set.of(GatewayRoute.NOTIFICATION_STREAM_PATH));
        publicStream.setAuthenticationPublicMethods(Set.of("GET"));
        publicStream.setStreaming(true);

        assertThat(publicStream.isStreamingConfigurationValid()).isFalse();
        assertThatThrownBy(() -> new GatewayRouteTable(properties(publicStream)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("streaming routes");
    }

    @Test
    void permitsRequestStreamingOnlyForTheExactAuthenticatedDocumentCreateOperation() {
        GatewayProperties.Route documents = new GatewayProperties.Route(
                "document-vault", GatewayRoute.DOCUMENT_UPLOAD_PATH, "https://documents.test");
        documents.setDocumentUploadStreaming(true);

        GatewayRoute route = new GatewayRouteTable(properties(documents))
                .resolve(GatewayRoute.DOCUMENT_UPLOAD_PATH)
                .orElseThrow();

        assertThat(route.documentUploadStreaming()).isTrue();
        assertThat(route.isExactDocumentUploadRequest(GatewayRoute.DOCUMENT_UPLOAD_PATH, "POST"))
                .isTrue();
        assertThat(route.isExactDocumentUploadRequest(GatewayRoute.DOCUMENT_UPLOAD_PATH, "PUT"))
                .isFalse();
        assertThat(route.isExactDocumentUploadRequest(
                        GatewayRoute.DOCUMENT_UPLOAD_PATH + "/123", "POST"))
                .isFalse();
    }

    @Test
    void rejectsDocumentRequestStreamingForAnyOtherPathOrAuthenticationException() {
        GatewayProperties.Route arbitraryPath = new GatewayProperties.Route(
                "unsafe-upload", "/api/v1/goals", "https://task-goal.test");
        arbitraryPath.setDocumentUploadStreaming(true);

        assertThat(arbitraryPath.isDocumentUploadStreamingConfigurationValid()).isFalse();
        assertThatThrownBy(() -> new GatewayRouteTable(properties(arbitraryPath)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("document upload streaming");

        GatewayProperties.Route publicDocuments = new GatewayProperties.Route(
                "document-vault", GatewayRoute.DOCUMENT_UPLOAD_PATH, "https://documents.test");
        publicDocuments.setDocumentUploadStreaming(true);
        publicDocuments.setAuthenticationPublicPaths(Set.of(GatewayRoute.DOCUMENT_UPLOAD_PATH));
        publicDocuments.setAuthenticationPublicMethods(Set.of("POST"));

        assertThat(publicDocuments.isDocumentUploadStreamingConfigurationValid()).isFalse();
        assertThatThrownBy(() -> new GatewayRouteTable(properties(publicDocuments)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("document upload streaming");
    }

    @Test
    void permitsOnlyExactAuthenticatedMediaUploadAndHlsOperations() {
        GatewayProperties.Route media = new GatewayProperties.Route(
                "media-assets", GatewayRoute.MEDIA_ASSETS_PATH_PREFIX, "https://media.test");
        media.setMediaUploadStreaming(true);
        media.setMediaHlsStreaming(true);

        GatewayRoute route = new GatewayRouteTable(properties(media))
                .resolve(GatewayRoute.MEDIA_ASSETS_PATH_PREFIX)
                .orElseThrow();
        String assetId = "11111111-1111-4111-8111-111111111111";

        assertThat(route.isExactMediaUploadRequest(
                        GatewayRoute.MEDIA_ASSETS_PATH_PREFIX + "/" + assetId + "/source", "PUT"))
                .isTrue();
        assertThat(route.isExactMediaHlsRequest(
                        GatewayRoute.MEDIA_ASSETS_PATH_PREFIX + "/" + assetId + "/hls/master.m3u8", "GET"))
                .isTrue();
        assertThat(route.isExactMediaHlsRequest(
                        GatewayRoute.MEDIA_ASSETS_PATH_PREFIX + "/" + assetId + "/hls/segments/segment-001.m4s",
                        "GET"))
                .isTrue();
        assertThat(route.isExactMediaHlsRequest(
                        GatewayRoute.MEDIA_ASSETS_PATH_PREFIX + "/" + assetId + "/hls/segments/segment-001.ts",
                        "GET"))
                .isTrue();

        assertThat(route.isExactMediaUploadRequest(
                        GatewayRoute.MEDIA_ASSETS_PATH_PREFIX + "/" + assetId + "/source", "POST"))
                .isFalse();
        assertThat(route.isExactMediaUploadRequest(
                        GatewayRoute.MEDIA_ASSETS_PATH_PREFIX + "/not-a-uuid/source", "PUT"))
                .isFalse();
        assertThat(route.isExactMediaHlsRequest(
                        GatewayRoute.MEDIA_ASSETS_PATH_PREFIX + "/" + assetId + "/hls/segments/../../secret.ts",
                        "GET"))
                .isFalse();
        assertThat(route.isExactMediaHlsRequest(
                        GatewayRoute.MEDIA_ASSETS_PATH_PREFIX + "/" + assetId + "/hls/segments/segment.mp4",
                        "GET"))
                .isFalse();
        assertThat(route.isExactMediaHlsRequest(
                        GatewayRoute.MEDIA_ASSETS_PATH_PREFIX + "/" + assetId + "/hls/master.m3u8", "HEAD"))
                .isFalse();
    }

    @Test
    void rejectsMediaStreamingForAnyOtherRouteOrAuthenticationException() {
        GatewayProperties.Route arbitraryPath = new GatewayProperties.Route(
                "unsafe-media", "/api/v1/goals", "https://task-goal.test");
        arbitraryPath.setMediaUploadStreaming(true);

        assertThat(arbitraryPath.isMediaUploadStreamingConfigurationValid()).isFalse();
        assertThatThrownBy(() -> new GatewayRouteTable(properties(arbitraryPath)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("media upload streaming");

        GatewayProperties.Route publicMedia = new GatewayProperties.Route(
                "media-assets", GatewayRoute.MEDIA_ASSETS_PATH_PREFIX, "https://media.test");
        publicMedia.setMediaHlsStreaming(true);
        publicMedia.setAuthenticationPublicPaths(Set.of(GatewayRoute.MEDIA_ASSETS_PATH_PREFIX));
        publicMedia.setAuthenticationPublicMethods(Set.of("GET"));

        assertThat(publicMedia.isMediaHlsStreamingConfigurationValid()).isFalse();
        assertThatThrownBy(() -> new GatewayRouteTable(properties(publicMedia)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("media HLS streaming");
    }

    private static GatewayProperties properties(GatewayProperties.Route... routes) {
        GatewayProperties properties = new GatewayProperties();
        properties.setRoutes(List.of(routes));
        return properties;
    }
}
