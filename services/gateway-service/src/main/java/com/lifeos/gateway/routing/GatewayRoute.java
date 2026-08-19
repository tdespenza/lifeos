package com.lifeos.gateway.routing;

import com.lifeos.gateway.config.GatewayProperties;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

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
        Set<String> authenticationPublicMethods,
        boolean streaming,
        boolean documentUploadStreaming,
        boolean mediaUploadStreaming,
        boolean mediaHlsStreaming) {

    /** The only route permitted to relay a live upstream byte stream. */
    public static final String NOTIFICATION_STREAM_PATH = "/api/v1/notifications/stream";
    /** The only route permitted to relay an inbound body without retaining it in the gateway heap. */
    public static final String DOCUMENT_UPLOAD_PATH = "/api/v1/documents";
    /** The one Media prefix that owns strictly enumerated upload and HLS relay operations. */
    public static final String MEDIA_ASSETS_PATH_PREFIX = "/api/v1/media/assets";
    /** The protected Assistant prefix with its own finite upstream response budget. */
    public static final String AI_ASSISTANT_PATH_PREFIX = "/api/v1/assistant";
    private static final Pattern CANONICAL_UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern HLS_SEGMENT_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern VERSIONED_PUBLIC_PATH_PREFIX =
            Pattern.compile("^/api/v[1-9][0-9]*/[^/]+(?:/.*)?$");

    /**
     * Normalizes method policy values and keeps all route policy sets immutable.
     */
    public GatewayRoute {
        authenticationRequiredMethods = normalizeAuthenticationMethods(authenticationRequiredMethods);
        authenticationPublicPaths = authenticationPublicPaths == null
                ? Set.of()
                : Set.copyOf(authenticationPublicPaths);
        authenticationPublicMethods = normalizeAuthenticationMethods(authenticationPublicMethods);
        if (AI_ASSISTANT_PATH_PREFIX.equals(pathPrefix)
                && !hasValidAiAssistantPolicy(
                        authenticationRequired,
                        authenticationRequiredMethods,
                        authenticationPublicPaths,
                        authenticationPublicMethods,
                        streaming,
                        documentUploadStreaming,
                        mediaUploadStreaming,
                        mediaHlsStreaming)) {
            throw new IllegalArgumentException(
                    "AI assistant route must be authenticated without method, public, or streaming exceptions");
        }
        if (streaming && !hasValidStreamingPolicy(
                pathPrefix,
                authenticationRequired,
                authenticationRequiredMethods,
                authenticationPublicPaths,
                authenticationPublicMethods)) {
            throw new IllegalArgumentException(
                    "streaming routes must be the exact authenticated notification SSE operation");
        }
        if (documentUploadStreaming && !hasValidDocumentUploadPolicy(
                pathPrefix,
                authenticationRequired,
                authenticationRequiredMethods,
                authenticationPublicPaths,
                authenticationPublicMethods,
                streaming)) {
            throw new IllegalArgumentException(
                    "document upload streaming must be the authenticated Document Vault route without exceptions");
        }
        if (mediaUploadStreaming && !hasValidMediaUploadPolicy(
                pathPrefix,
                authenticationRequired,
                authenticationRequiredMethods,
                authenticationPublicPaths,
                authenticationPublicMethods,
                streaming,
                documentUploadStreaming)) {
            throw new IllegalArgumentException(
                    "media upload streaming must be the authenticated Media assets route without exceptions");
        }
        if (mediaHlsStreaming && !hasValidMediaHlsPolicy(
                pathPrefix,
                authenticationRequired,
                authenticationRequiredMethods,
                authenticationPublicPaths,
                authenticationPublicMethods,
                streaming,
                documentUploadStreaming)) {
            throw new IllegalArgumentException(
                    "media HLS streaming must be the authenticated Media assets route without exceptions");
        }
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
        this(
                id,
                pathPrefix,
                upstream,
                authenticationRequired,
                authenticationRequiredMethods,
                Set.of(),
                Set.of(),
                false,
                false,
                false,
                false);
    }

    /**
     * Creates a route with explicit public-operation exceptions, retaining the historic
     * non-streaming default for call sites that do not opt into the isolated SSE path.
     */
    public GatewayRoute(
            String id,
            String pathPrefix,
            URI upstream,
            boolean authenticationRequired,
            Set<String> authenticationRequiredMethods,
            Set<String> authenticationPublicPaths,
            Set<String> authenticationPublicMethods) {
        this(
                id,
                pathPrefix,
                upstream,
                authenticationRequired,
                authenticationRequiredMethods,
                authenticationPublicPaths,
                authenticationPublicMethods,
                false,
                false,
                false,
                false);
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
                route.getAuthenticationPublicMethods(),
                route.isStreaming(),
                route.isDocumentUploadStreaming(),
                route.isMediaUploadStreaming(),
                route.isMediaHlsStreaming());
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
     * Returns whether this route accepts the one intentionally supported SSE operation.
     *
     * <p>The caller must use this predicate before forwarding so path descendants and other
     * methods cannot inherit byte-streaming behavior from a prefix match.
     *
     * @param requestPath inbound path without the servlet context path
     * @param method inbound HTTP method
     * @return whether this is exactly {@code GET /api/v1/notifications/stream}
     */
    public boolean isExactStreamingRequest(String requestPath, String method) {
        return streaming
                && NOTIFICATION_STREAM_PATH.equals(requestPath)
                && "GET".equalsIgnoreCase(method);
    }

    /**
     * Returns whether this is the one deliberately supported inbound request-streaming operation.
     *
     * <p>The caller must use this predicate before forwarding. A Document Vault prefix also owns
     * small metadata/read operations, but only its exact multipart create operation may bypass the
     * ordinary one-mebibyte request buffer. Path descendants and every other method remain on the
     * bounded buffered proxy path.
     *
     * @param requestPath inbound path without the servlet context path
     * @param method inbound HTTP method
     * @return whether this is exactly {@code POST /api/v1/documents}
     */
    public boolean isExactDocumentUploadRequest(String requestPath, String method) {
        return documentUploadStreaming
                && DOCUMENT_UPLOAD_PATH.equals(requestPath)
                && "POST".equalsIgnoreCase(method);
    }

    /**
     * Returns whether this is the one deliberately supported Media request-streaming operation.
     *
     * <p>A Media assets route also owns small metadata and session-adjacent reads, but only a
     * canonical-UUID source upload may bypass the ordinary one-mebibyte request buffer. A path
     * descendant, non-UUID asset identifier, or every other method remains on the bounded
     * ordinary proxy path.
     *
     * @param requestPath inbound path without the servlet context path
     * @param method inbound HTTP method
     * @return whether this is exactly the authenticated asset-source {@code PUT}
     */
    public boolean isExactMediaUploadRequest(String requestPath, String method) {
        String suffix = mediaAssetSuffix(requestPath);
        return mediaUploadStreaming
                && "PUT".equalsIgnoreCase(method)
                && "/source".equals(suffix);
    }

    /**
     * Returns whether this is one of the two deliberately supported Media HLS response streams.
     *
     * <p>The manifest and segment names are structurally validated before they can select the
     * streaming path. All other Media paths—including source upload and JSON operations—retain
     * the ordinary bounded proxy behavior.
     *
     * @param requestPath inbound path without the servlet context path
     * @param method inbound HTTP method
     * @return whether this is exactly a reviewed HLS manifest or segment {@code GET}
     */
    public boolean isExactMediaHlsRequest(String requestPath, String method) {
        if (!mediaHlsStreaming || !"GET".equalsIgnoreCase(method)) {
            return false;
        }
        String suffix = mediaAssetSuffix(requestPath);
        if ("/hls/master.m3u8".equals(suffix)) {
            return true;
        }
        return suffix != null
                && suffix.startsWith("/hls/segments/")
                && hasValidHlsSegmentName(suffix.substring("/hls/segments/".length()));
    }

    /**
     * Returns whether this route owns the Assistant-only outbound timeout and retry policy.
     *
     * <p>Construction rejects an unprotected or special-streaming route at this prefix. The
     * predicate is based on that fixed public prefix, rather than a deployment-selected route ID,
     * so an accidental route-ID rename cannot silently make Assistant traffic inherit the generic
     * five-second client deadline.
     *
     * @return whether this is the configured Assistant route
     */
    public boolean isAiAssistantRoute() {
        return AI_ASSISTANT_PATH_PREFIX.equals(pathPrefix);
    }

    /**
     * Returns the stable virtual resilience key for Media upload isolation.
     *
     * @return finite Media-upload resilience key
     */
    String mediaUploadResilienceId() {
        return id + "-media-upload";
    }

    /**
     * Returns the stable virtual resilience key for Media HLS isolation.
     *
     * @return finite Media-HLS resilience key
     */
    String mediaHlsResilienceId() {
        return id + "-media-hls";
    }

    private static String mediaAssetSuffix(String requestPath) {
        String prefix = MEDIA_ASSETS_PATH_PREFIX + "/";
        if (requestPath == null || !requestPath.startsWith(prefix)) {
            return null;
        }
        int idEnd = prefix.length() + 36;
        if (requestPath.length() < idEnd
                || !CANONICAL_UUID.matcher(requestPath.substring(prefix.length(), idEnd)).matches()) {
            return null;
        }
        return requestPath.substring(idEnd);
    }

    private static boolean hasValidHlsSegmentName(String segmentName) {
        return HLS_SEGMENT_NAME.matcher(segmentName).matches()
                && !segmentName.contains("..")
                && (segmentName.endsWith(".m4s") || segmentName.endsWith(".ts"));
    }

    /**
     * Checks the complete public path-prefix rule used by configuration binding and route
     * materialization.
     *
     * @param value configured path prefix
     * @return whether the value is a safe named versioned public path prefix
     */
    public static boolean isValidPathPrefix(String value) {
        return value != null
                && !value.isBlank()
                && value.startsWith("/")
                && VERSIONED_PUBLIC_PATH_PREFIX.matcher(value).matches()
                && !value.contains("//")
                && !value.contains("?")
                && !value.contains("#")
                && !value.contains("*")
                && !value.contains("{")
                && !value.contains("}")
                && !value.endsWith("/");
    }

    /**
     * Checks the complete fixed upstream origin rule used by configuration binding and route
     * materialization. Remote upstreams must use HTTPS; cleartext HTTP is limited to literal
     * loopback hosts for local development.
     *
     * @param value configured upstream origin
     * @return whether the value is a safe absolute HTTPS origin or a loopback HTTP origin
     */
    public static boolean isValidUpstream(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            if (!uri.isAbsolute()
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || !(uri.getPath() == null || uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
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

    private static String normalizePathPrefix(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("route pathPrefix must not be blank");
        }
        if (!isValidPathPrefix(value)) {
            throw new IllegalArgumentException("route pathPrefix is not a valid versioned public path");
        }
        return value;
    }

    private static URI normalizeUpstream(String value) {
        if (!isValidUpstream(value)) {
            throw new IllegalArgumentException(
                    "route upstream must be an HTTPS origin unless its host is loopback");
        }
        return URI.create(value);
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

    private static Set<String> normalizeAuthenticationMethods(Set<String> methods) {
        if (methods == null || methods.isEmpty()) {
            return Set.of();
        }
        return methods.stream()
                .map(method -> method.toUpperCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static boolean hasValidStreamingPolicy(
            String pathPrefix,
            boolean authenticationRequired,
            Set<String> authenticationRequiredMethods,
            Set<String> authenticationPublicPaths,
            Set<String> authenticationPublicMethods) {
        return NOTIFICATION_STREAM_PATH.equals(pathPrefix)
                && authenticationRequired
                && authenticationRequiredMethods.equals(Set.of("GET"))
                && authenticationPublicPaths.isEmpty()
                && authenticationPublicMethods.isEmpty();
    }

    private static boolean hasValidAiAssistantPolicy(
            boolean authenticationRequired,
            Set<String> authenticationRequiredMethods,
            Set<String> authenticationPublicPaths,
            Set<String> authenticationPublicMethods,
            boolean streaming,
            boolean documentUploadStreaming,
            boolean mediaUploadStreaming,
            boolean mediaHlsStreaming) {
        return authenticationRequired
                && authenticationRequiredMethods.isEmpty()
                && authenticationPublicPaths.isEmpty()
                && authenticationPublicMethods.isEmpty()
                && !streaming
                && !documentUploadStreaming
                && !mediaUploadStreaming
                && !mediaHlsStreaming;
    }

    private static boolean hasValidDocumentUploadPolicy(
            String pathPrefix,
            boolean authenticationRequired,
            Set<String> authenticationRequiredMethods,
            Set<String> authenticationPublicPaths,
            Set<String> authenticationPublicMethods,
            boolean streaming) {
        return DOCUMENT_UPLOAD_PATH.equals(pathPrefix)
                && authenticationRequired
                && authenticationRequiredMethods.isEmpty()
                && authenticationPublicPaths.isEmpty()
                && authenticationPublicMethods.isEmpty()
                && !streaming;
    }

    private static boolean hasValidMediaUploadPolicy(
            String pathPrefix,
            boolean authenticationRequired,
            Set<String> authenticationRequiredMethods,
            Set<String> authenticationPublicPaths,
            Set<String> authenticationPublicMethods,
            boolean streaming,
            boolean documentUploadStreaming) {
        return MEDIA_ASSETS_PATH_PREFIX.equals(pathPrefix)
                && authenticationRequired
                && authenticationRequiredMethods.isEmpty()
                && authenticationPublicPaths.isEmpty()
                && authenticationPublicMethods.isEmpty()
                && !streaming
                && !documentUploadStreaming;
    }

    private static boolean hasValidMediaHlsPolicy(
            String pathPrefix,
            boolean authenticationRequired,
            Set<String> authenticationRequiredMethods,
            Set<String> authenticationPublicPaths,
            Set<String> authenticationPublicMethods,
            boolean streaming,
            boolean documentUploadStreaming) {
        return MEDIA_ASSETS_PATH_PREFIX.equals(pathPrefix)
                && authenticationRequired
                && authenticationRequiredMethods.isEmpty()
                && authenticationPublicPaths.isEmpty()
                && authenticationPublicMethods.isEmpty()
                && !streaming
                && !documentUploadStreaming;
    }
}
