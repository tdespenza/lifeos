package com.lifeos.gateway.config;

import com.lifeos.gateway.routing.GatewayRoute;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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

    private static final Set<String> SUPPORTED_GATEWAY_METHODS = Set.of(
            "GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");

    @Valid
    @NotEmpty(message = "at least one gateway route must be configured")
    private List<Route> routes = new ArrayList<>();

    @Min(value = 1, message = "maxRequestBodyBytes must be positive")
    private long maxRequestBodyBytes = 1_048_576L;

    @Min(value = 1, message = "maxConcurrentRequestBodyBuffers must be positive")
    @Max(value = 4096, message = "maxConcurrentRequestBodyBuffers must be bounded")
    private int maxConcurrentRequestBodyBuffers = 64;

    @Min(value = 1, message = "maxRequestBodyBufferBytes must be positive")
    private long maxRequestBodyBufferBytes = 67_108_864L;

    @Min(value = 1, message = "maxConcurrentResponseBuffers must be positive")
    @Max(value = 4096, message = "maxConcurrentResponseBuffers must be bounded")
    private int maxConcurrentResponseBuffers = 64;

    @Min(value = 1, message = "maxResponseBodyBytes must be positive")
    private long maxResponseBodyBytes = 10_485_760L;

    @Min(value = 1, message = "maxResponseBufferBytes must be positive")
    private long maxResponseBufferBytes = 671_088_640L;

    @NotNull(message = "connectTimeout must be configured")
    private Duration connectTimeout = Duration.ofSeconds(2);

    @NotNull(message = "readTimeout must be configured")
    private Duration readTimeout = Duration.ofSeconds(5);

    @NotNull(message = "inboundRequestTimeout must be configured")
    private Duration inboundRequestTimeout = Duration.ofSeconds(10);

    /** Optional shared HMAC used to prove gateway-authenticated subjects to analytics. */
    private String analyticsProofSecret = "";

    @Valid
    private final Streaming streaming = new Streaming();

    @Valid
    private final DocumentUpload documentUpload = new DocumentUpload();

    @Valid
    private final MediaUpload mediaUpload = new MediaUpload();

    @Valid
    private final MediaHls mediaHls = new MediaHls();

    @Valid
    private final AiAssistant aiAssistant = new AiAssistant();

    @Valid
    private final RateLimit rateLimit = new RateLimit();

    @Valid
    private final Upstream upstream = new Upstream();

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
     * Returns the maximum number of request bodies that may be buffered concurrently.
     *
     * @return request-body buffering admission capacity
     */
    public int getMaxConcurrentRequestBodyBuffers() {
        return maxConcurrentRequestBodyBuffers;
    }

    /**
     * Sets the request-body buffering admission capacity during configuration binding.
     *
     * @param maxConcurrentRequestBodyBuffers maximum concurrent request-body buffers
     */
    public void setMaxConcurrentRequestBodyBuffers(int maxConcurrentRequestBodyBuffers) {
        this.maxConcurrentRequestBodyBuffers = maxConcurrentRequestBodyBuffers;
    }

    /**
     * Returns the aggregate byte budget for retained inbound request bodies.
     *
     * @return aggregate request-body buffer budget in bytes
     */
    public long getMaxRequestBodyBufferBytes() {
        return maxRequestBodyBufferBytes;
    }

    /**
     * Sets the aggregate inbound request-body buffer budget during configuration binding.
     *
     * @param maxRequestBodyBufferBytes aggregate request-body buffer budget in bytes
     */
    public void setMaxRequestBodyBufferBytes(long maxRequestBodyBufferBytes) {
        this.maxRequestBodyBufferBytes = maxRequestBodyBufferBytes;
    }

    /**
     * Returns the maximum number of buffered responses that may be retained through client writes.
     *
     * @return response-buffer admission capacity
     */
    public int getMaxConcurrentResponseBuffers() {
        return maxConcurrentResponseBuffers;
    }

    /**
     * Sets the response-buffer admission capacity during configuration binding.
     *
     * @param maxConcurrentResponseBuffers maximum concurrent response buffers
     */
    public void setMaxConcurrentResponseBuffers(int maxConcurrentResponseBuffers) {
        this.maxConcurrentResponseBuffers = maxConcurrentResponseBuffers;
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
     * Returns the aggregate byte budget for retained downstream response buffers.
     *
     * @return aggregate response-buffer budget in bytes
     */
    public long getMaxResponseBufferBytes() {
        return maxResponseBufferBytes;
    }

    /**
     * Sets the aggregate downstream response-buffer budget during configuration binding.
     *
     * @param maxResponseBufferBytes aggregate response-buffer budget in bytes
     */
    public void setMaxResponseBufferBytes(long maxResponseBufferBytes) {
        this.maxResponseBufferBytes = maxResponseBufferBytes;
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
     * Returns the inbound Tomcat connection and request-body upload timeout.
     *
     * <p>The gateway applies this timeout to request-line/header reads, idle keep-alive
     * connections, and request-body upload reads. It bounds a stalled client's socket read so a
     * request-body buffer permit cannot be retained indefinitely without input progress.
     *
     * @return inbound request timeout
     */
    public Duration getInboundRequestTimeout() {
        return inboundRequestTimeout;
    }

    /**
     * Sets the inbound Tomcat connection and request-body upload timeout.
     *
     * @param inboundRequestTimeout bounded inbound request timeout
     */
    public void setInboundRequestTimeout(Duration inboundRequestTimeout) {
        this.inboundRequestTimeout = inboundRequestTimeout;
    }

    public String getAnalyticsProofSecret() {
        return analyticsProofSecret;
    }

    public void setAnalyticsProofSecret(String analyticsProofSecret) {
        this.analyticsProofSecret = analyticsProofSecret == null ? "" : analyticsProofSecret;
    }

    /**
     * Returns the deliberately narrow SSE forwarding limits.
     *
     * <p>These limits are independent from ordinary buffered HTTP forwarding because a live event
     * stream cannot safely use the gateway's five-second response deadline or ten-mebibyte response
     * buffer. Only the exact notification stream route may use this configuration.
     *
     * @return bounded streaming configuration
     */
    public Streaming getStreaming() {
        return streaming;
    }

    /**
     * Returns the deliberately narrow request-streaming limits for document creation.
     *
     * <p>Ordinary gateway requests are buffered under {@link #getMaxRequestBodyBytes()}. Only
     * the exact authenticated {@code POST /api/v1/documents} operation may use this independent
     * bounded relay policy; it exists because multipart document content must not be retained in
     * the gateway heap.
     *
     * @return bounded document-upload configuration
     */
    public DocumentUpload getDocumentUpload() {
        return documentUpload;
    }

    /**
     * Returns the deliberately narrow request-streaming limits for Media source uploads.
     *
     * <p>Only the authenticated versioned {@code PUT /api/v1/media/assets/{id}/source}
     * operation can use this policy. It is isolated from ordinary Media JSON requests and from
     * Document Vault uploads because its reviewed byte limit and deadline are materially larger.
     *
     * @return bounded Media source-upload configuration
     */
    public MediaUpload getMediaUpload() {
        return mediaUpload;
    }

    /**
     * Returns the deliberately narrow response-streaming limits for Media HLS reads.
     *
     * <p>Only the authenticated HLS manifest and segment read operations can use this policy.
     * It relays fixed-size chunks under a finite byte and connection bound; it does not make the
     * generic Media API a streaming proxy.
     *
     * @return bounded Media HLS response-streaming configuration
     */
    public MediaHls getMediaHls() {
        return mediaHls;
    }

    /**
     * Returns the isolated deadline for the AI assistant route. The AI service performs a bounded
     * Identity workload check before its provider call, so it must not inherit the ordinary
     * five-second buffered proxy deadline.
     *
     * @return AI assistant route timeout configuration
     */
    public AiAssistant getAiAssistant() {
        return aiAssistant;
    }

    /**
     * Returns the gateway-wide Redis rate-limit settings. The route identifier is included in
     * every limiter key, so one configured budget is independently enforced for each route.
     *
     * @return Redis rate-limit settings
     */
    public RateLimit getRateLimit() {
        return rateLimit;
    }

    /**
     * Returns upstream dependency-isolation settings.
     *
     * @return upstream bulkhead and circuit-breaker settings
     */
    public Upstream getUpstream() {
        return upstream;
    }

    /**
     * Validates outbound timeout values before constructing the HTTP client.
     *
     * @return {@code true} when both timeouts are bounded and positive
     */
    @AssertTrue(message = "gateway timeouts must be positive and no greater than 60 seconds")
    public boolean isTimeoutsValid() {
        return isBoundedPositive(connectTimeout) && isBoundedPositive(readTimeout);
    }

    /**
     * Validates that the retry budget can always contain one fully bounded outbound invocation.
     *
     * <p>The retry policy reserves the connection deadline plus the response-read deadline before
     * it starts another attempt. Rejecting a shorter total timeout at startup prevents a deployment
     * from configuring a logical request budget that cannot even contain its first outbound call.
     *
     * @return {@code true} when the retry total timeout can accommodate one upstream call
     */
    @AssertTrue(message = "upstream.retry.totalTimeout must accommodate one full connection-and-read attempt")
    public boolean isRetryTimeoutBudgetValid() {
        Duration totalTimeout = upstream.getRetry().getTotalTimeout();
        if (!isBoundedPositive(totalTimeout)
                || !isBoundedPositive(connectTimeout)
                || !isBoundedPositive(readTimeout)) {
            return false;
        }
        return totalTimeout.toNanos() - connectTimeout.toNanos() >= readTimeout.toNanos();
    }

    /**
     * Validates the inbound Tomcat request deadline. Tomcat accepts timeout values in whole
     * milliseconds, so a positive sub-millisecond duration is not a usable bound.
     *
     * @return {@code true} when the inbound timeout is a usable, bounded duration
     */
    @AssertTrue(message = "inboundRequestTimeout must be at least one millisecond and no greater than 60 seconds")
    public boolean isInboundRequestTimeoutValid() {
        return isBoundedPositive(inboundRequestTimeout)
                && inboundRequestTimeout.compareTo(Duration.ofMillis(1)) >= 0;
    }

    /**
     * Validates that the maximum request-buffer count and size fit the aggregate byte budget.
     *
     * @return {@code true} when the request-buffer product is within its budget
     */
    @AssertTrue(message = "request body buffer count and size must fit maxRequestBodyBufferBytes")
    public boolean isRequestBodyBufferBudgetValid() {
        return productWithinBudget(
                maxConcurrentRequestBodyBuffers, maxRequestBodyBytes, maxRequestBodyBufferBytes);
    }

    /**
     * Validates that the maximum response-buffer count and size fit the aggregate byte budget.
     *
     * @return {@code true} when the response-buffer product is within its budget
     */
    @AssertTrue(message = "response buffer count and size must fit maxResponseBufferBytes")
    public boolean isResponseBufferBudgetValid() {
        return productWithinBudget(
                maxConcurrentResponseBuffers, maxResponseBodyBytes, maxResponseBufferBytes);
    }

    private static boolean productWithinBudget(long count, long itemBytes, long budgetBytes) {
        return count > 0 && itemBytes > 0 && budgetBytes > 0 && count <= budgetBytes / itemBytes;
    }

    private static boolean isBoundedPositive(Duration duration) {
        return isBoundedPositive(duration, Duration.ofSeconds(60));
    }

    private static boolean isBoundedPositive(Duration duration, Duration maximum) {
        return duration != null
                && !duration.isZero()
                && !duration.isNegative()
                && duration.compareTo(maximum) <= 0;
    }

    /**
     * Resource and lifetime limits for the single supported server-sent-events route.
     *
     * <p>The gateway uses this policy only for {@code GET /api/v1/notifications/stream}. It is a
     * connection admission bound, not a response-body buffer: the forwarder relays fixed-size byte
     * chunks and retains no event history. A finite read lifetime forces clients to reconnect using
     * {@code Last-Event-ID}, which is the notification service's recovery contract.
     */
    public static class Streaming {

        @Min(value = 1, message = "streaming.maxConcurrentConnections must be positive")
        @Max(value = 4096, message = "streaming.maxConcurrentConnections must be bounded")
        private int maxConcurrentConnections = 32;

        @NotNull(message = "streaming.connectTimeout must be configured")
        private Duration connectTimeout = Duration.ofSeconds(2);

        @NotNull(message = "streaming.readLifetime must be configured")
        private Duration readLifetime = Duration.ofMinutes(30);

        /**
         * Returns the non-waiting global admission bound for live SSE connections.
         *
         * @return maximum in-flight SSE connections per gateway instance
         */
        public int getMaxConcurrentConnections() {
            return maxConcurrentConnections;
        }

        /**
         * Sets the non-waiting global SSE admission bound during configuration binding.
         *
         * @param maxConcurrentConnections maximum in-flight SSE connections
         */
        public void setMaxConcurrentConnections(int maxConcurrentConnections) {
            this.maxConcurrentConnections = maxConcurrentConnections;
        }

        /**
         * Returns the outbound TCP/TLS connection deadline for SSE.
         *
         * @return connection deadline
         */
        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        /**
         * Sets the outbound SSE connection deadline during configuration binding.
         *
         * @param connectTimeout connection deadline
         */
        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        /**
         * Returns the maximum lifetime of one upstream SSE response.
         *
         * @return finite response-read lifetime
         */
        public Duration getReadLifetime() {
            return readLifetime;
        }

        /**
         * Sets the maximum lifetime of one upstream SSE response during configuration binding.
         *
         * @param readLifetime finite response-read lifetime
         */
        public void setReadLifetime(Duration readLifetime) {
            this.readLifetime = readLifetime;
        }

        /**
         * Keeps long-lived connections finite while permitting a practical reconnect interval.
         *
         * @return whether both streaming deadlines are positive and within their separate bounds
         */
        @AssertTrue(message = "streaming connectTimeout must be positive and no greater than 60 seconds; readLifetime must be positive and no greater than one hour")
        public boolean isTimeoutsValid() {
            return isBoundedPositive(connectTimeout, Duration.ofSeconds(60))
                    && isBoundedPositive(readLifetime, Duration.ofHours(1));
        }
    }

    /**
     * Resource and timeout limits for the exact document-vault multipart create operation.
     *
     * <p>This is intentionally not a general-purpose request streaming setting. The hard upper
     * bound includes multipart framing overhead above Document Vault's ten-mebibyte file limit,
     * and the fair admission semaphore in the forwarder bounds the number of servlet input streams
     * that may be coupled to outbound requests at one time.
     */
    public static class DocumentUpload {

        /** Document Vault's ten-mebibyte payload cap plus fixed multipart metadata overhead. */
        public static final long MAX_DOCUMENT_UPLOAD_BODY_BYTES = 11_010_048L;

        @Min(value = 1, message = "documentUpload.maxConcurrentUploads must be positive")
        @Max(value = 64, message = "documentUpload.maxConcurrentUploads must be bounded")
        private int maxConcurrentUploads = 8;

        @Min(value = 1, message = "documentUpload.maxRequestBodyBytes must be positive")
        @Max(
                value = MAX_DOCUMENT_UPLOAD_BODY_BYTES,
                message = "documentUpload.maxRequestBodyBytes must not exceed the reviewed Document Vault multipart bound")
        private long maxRequestBodyBytes = MAX_DOCUMENT_UPLOAD_BODY_BYTES;

        @NotNull(message = "documentUpload.connectTimeout must be configured")
        private Duration connectTimeout = Duration.ofSeconds(2);

        @NotNull(message = "documentUpload.readTimeout must be configured")
        private Duration readTimeout = Duration.ofSeconds(45);

        /**
         * Returns the non-waiting per-instance admission limit for streamed uploads.
         *
         * @return maximum in-flight document upload relays
         */
        public int getMaxConcurrentUploads() {
            return maxConcurrentUploads;
        }

        /**
         * Sets the non-waiting document-upload admission limit.
         *
         * @param maxConcurrentUploads maximum in-flight upload relays
         */
        public void setMaxConcurrentUploads(int maxConcurrentUploads) {
            this.maxConcurrentUploads = maxConcurrentUploads;
        }

        /**
         * Returns the request-size ceiling including multipart framing overhead.
         *
         * @return maximum inbound document-create request bytes
         */
        public long getMaxRequestBodyBytes() {
            return maxRequestBodyBytes;
        }

        /**
         * Sets the bounded document-create request-size ceiling.
         *
         * @param maxRequestBodyBytes maximum inbound bytes including multipart framing
         */
        public void setMaxRequestBodyBytes(long maxRequestBodyBytes) {
            this.maxRequestBodyBytes = maxRequestBodyBytes;
        }

        /**
         * Returns the outbound connection deadline for the upload relay.
         *
         * @return connection deadline
         */
        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        /**
         * Sets the outbound connection deadline for the upload relay.
         *
         * @param connectTimeout connection deadline
         */
        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        /**
         * Returns the finite request/response lifetime for one upload relay.
         *
         * @return upload relay lifetime
         */
        public Duration getReadTimeout() {
            return readTimeout;
        }

        /**
         * Sets the finite request/response lifetime for one upload relay.
         *
         * @param readTimeout upload relay lifetime
         */
        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }

        /**
         * Ensures document upload relays have finite, practical deadlines.
         *
         * <p>The default exceeds Document Vault's thirty-second service-side upload deadline so
         * the relay can receive a domain response, while remaining bounded to prevent an
         * indefinitely occupied outbound connection.
         *
         * @return whether both upload deadlines are positive, ordered, and bounded
         */
        @AssertTrue(message = "documentUpload timeouts must be positive, ordered, and no greater than 60 seconds")
        public boolean isTimeoutsValid() {
            return isBoundedPositive(connectTimeout)
                    && isBoundedPositive(readTimeout)
                    && readTimeout.compareTo(connectTimeout) >= 0;
        }
    }

    /**
     * Resource and timeout limits for the exact Media source-upload operation.
     *
     * <p>The 51 MiB whole-request ceiling is the Media service's 50 MiB verified source limit
     * plus reviewed multipart framing allowance. The gateway copies a request through a fixed
     * buffer and never automatically retries it because the service alone owns idempotency.
     */
    public static class MediaUpload {

        /** Media's 50 MiB source cap plus one MiB multipart framing allowance. */
        public static final long MAX_MEDIA_UPLOAD_BODY_BYTES = 53_477_376L;

        @Min(value = 1, message = "mediaUpload.maxConcurrentUploads must be positive")
        @Max(value = 32, message = "mediaUpload.maxConcurrentUploads must be bounded")
        private int maxConcurrentUploads = 4;

        @Min(value = 1, message = "mediaUpload.maxRequestBodyBytes must be positive")
        @Max(
                value = MAX_MEDIA_UPLOAD_BODY_BYTES,
                message = "mediaUpload.maxRequestBodyBytes must not exceed the reviewed Media multipart bound")
        private long maxRequestBodyBytes = MAX_MEDIA_UPLOAD_BODY_BYTES;

        @NotNull(message = "mediaUpload.connectTimeout must be configured")
        private Duration connectTimeout = Duration.ofSeconds(2);

        @NotNull(message = "mediaUpload.readTimeout must be configured")
        private Duration readTimeout = Duration.ofSeconds(75);

        /**
         * Returns the non-waiting per-instance admission limit for streamed Media uploads.
         *
         * @return maximum in-flight Media upload relays
         */
        public int getMaxConcurrentUploads() {
            return maxConcurrentUploads;
        }

        /**
         * Sets the non-waiting Media upload admission limit.
         *
         * @param maxConcurrentUploads maximum concurrent upload relays
         */
        public void setMaxConcurrentUploads(int maxConcurrentUploads) {
            this.maxConcurrentUploads = maxConcurrentUploads;
        }

        /**
         * Returns the request-size ceiling including multipart framing overhead.
         *
         * @return maximum inbound Media source-upload request bytes
         */
        public long getMaxRequestBodyBytes() {
            return maxRequestBodyBytes;
        }

        /**
         * Sets the bounded Media source-upload request-size ceiling.
         *
         * @param maxRequestBodyBytes maximum inbound bytes including multipart framing
         */
        public void setMaxRequestBodyBytes(long maxRequestBodyBytes) {
            this.maxRequestBodyBytes = maxRequestBodyBytes;
        }

        /**
         * Returns the outbound connection deadline for the Media upload relay.
         *
         * @return connection deadline
         */
        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        /**
         * Sets the outbound connection deadline for the Media upload relay.
         *
         * @param connectTimeout connection deadline
         */
        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        /**
         * Returns the finite request/response lifetime for one Media upload relay.
         *
         * @return upload relay lifetime
         */
        public Duration getReadTimeout() {
            return readTimeout;
        }

        /**
         * Sets the finite request/response lifetime for one Media upload relay.
         *
         * @param readTimeout upload relay lifetime
         */
        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }

        /**
         * Ensures the upload relay outlives the service's 60-second bounded deadline while
         * retaining a finite transfer margin.
         *
         * @return whether both upload deadlines are positive, ordered, and bounded
         */
        @AssertTrue(message = "mediaUpload timeouts must be positive, ordered, and no greater than 120 seconds")
        public boolean isTimeoutsValid() {
            return isBoundedPositive(connectTimeout)
                    && isBoundedPositive(readTimeout, Duration.ofSeconds(120))
                    && readTimeout.compareTo(Duration.ofSeconds(60)) >= 0
                    && readTimeout.compareTo(connectTimeout) >= 0;
        }
    }

    /**
     * Resource and timeout limits for the AI assistant route.
     *
     * <p>The assistant performs up to three seconds of Identity validation and up to five seconds
     * of provider work. A separate bounded deadline preserves the service's structured timeout
     * response instead of converting a valid provider timeout into a gateway timeout.
     */
    public static class AiAssistant {

        /** Maximum assistant-side Identity validation lifetime. */
        private static final Duration IDENTITY_VALIDATION_BUDGET = Duration.ofSeconds(3);

        /** Maximum assistant-side provider invocation lifetime. */
        private static final Duration PROVIDER_WORK_BUDGET = Duration.ofSeconds(5);

        /** Transfer and error-serialization margin after the reviewed service work budget. */
        private static final Duration RESPONSE_MARGIN = Duration.ofSeconds(2);

        @NotNull(message = "aiAssistant.connectTimeout must be configured")
        private Duration connectTimeout = Duration.ofSeconds(2);

        @NotNull(message = "aiAssistant.readTimeout must be configured")
        private Duration readTimeout = Duration.ofSeconds(12);

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }

        /**
         * Ensures the gateway cannot preempt the assistant's bounded internal work.
         *
         * <p>The JDK client's response deadline begins before the connection is fully established,
         * so the gateway must reserve its own connection timeout in addition to the assistant's
         * three-second Identity validation, five-second provider call, and a two-second transfer
         * margin. The default is therefore {@code 2s + 3s + 5s + 2s = 12s}.
         *
         * @return whether the assistant exchange lifetime is safe and bounded
         */
        @AssertTrue(message = "aiAssistant readTimeout must cover connect timeout, 3 seconds Identity validation, 5 seconds provider work, and a 2 second margin within 60 seconds")
        public boolean isTimeoutsValid() {
            return isBoundedPositive(connectTimeout)
                    && isBoundedPositive(readTimeout)
                    && readTimeout.compareTo(connectTimeout
                                    .plus(IDENTITY_VALIDATION_BUDGET)
                                    .plus(PROVIDER_WORK_BUDGET)
                                    .plus(RESPONSE_MARGIN))
                            >= 0;
        }
    }

    /**
     * Resource and lifetime limits for the exact Media HLS manifest and segment reads.
     *
     * <p>The gateway does not retain media responses in the ordinary response buffer. It streams
     * a fixed-size relay buffer under this independent admission limit and stops an upstream that
     * exceeds the reviewed largest-segment bound.
     */
    public static class MediaHls {

        /** Largest reviewed Media HLS response: one 25 MiB segment. */
        public static final long MAX_MEDIA_HLS_RESPONSE_BYTES = 26_214_400L;

        @Min(value = 1, message = "mediaHls.maxConcurrentStreams must be positive")
        @Max(value = 64, message = "mediaHls.maxConcurrentStreams must be bounded")
        private int maxConcurrentStreams = 8;

        @Min(value = 1, message = "mediaHls.maxResponseBodyBytes must be positive")
        @Max(
                value = MAX_MEDIA_HLS_RESPONSE_BYTES,
                message = "mediaHls.maxResponseBodyBytes must not exceed the reviewed Media HLS bound")
        private long maxResponseBodyBytes = MAX_MEDIA_HLS_RESPONSE_BYTES;

        @NotNull(message = "mediaHls.connectTimeout must be configured")
        private Duration connectTimeout = Duration.ofSeconds(2);

        @NotNull(message = "mediaHls.readTimeout must be configured")
        private Duration readTimeout = Duration.ofSeconds(60);

        /**
         * Returns the non-waiting per-instance HLS response-stream admission limit.
         *
         * @return maximum in-flight HLS response streams
         */
        public int getMaxConcurrentStreams() {
            return maxConcurrentStreams;
        }

        /**
         * Sets the non-waiting HLS response-stream admission limit.
         *
         * @param maxConcurrentStreams maximum concurrent HLS streams
         */
        public void setMaxConcurrentStreams(int maxConcurrentStreams) {
            this.maxConcurrentStreams = maxConcurrentStreams;
        }

        /**
         * Returns the hard upstream HLS response ceiling.
         *
         * @return maximum manifest or segment response bytes
         */
        public long getMaxResponseBodyBytes() {
            return maxResponseBodyBytes;
        }

        /**
         * Sets the hard upstream HLS response ceiling.
         *
         * @param maxResponseBodyBytes maximum manifest or segment response bytes
         */
        public void setMaxResponseBodyBytes(long maxResponseBodyBytes) {
            this.maxResponseBodyBytes = maxResponseBodyBytes;
        }

        /**
         * Returns the outbound connection deadline for an HLS response.
         *
         * @return connection deadline
         */
        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        /**
         * Sets the outbound connection deadline for an HLS response.
         *
         * @param connectTimeout connection deadline
         */
        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        /**
         * Returns the finite response-read lifetime for one HLS relay.
         *
         * @return HLS read deadline
         */
        public Duration getReadTimeout() {
            return readTimeout;
        }

        /**
         * Sets the finite response-read lifetime for one HLS relay.
         *
         * @param readTimeout HLS read deadline
         */
        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }

        /**
         * Ensures response streams remain finite and no longer than the reviewed one-minute
         * application read ceiling.
         *
         * @return whether both HLS deadlines are positive, ordered, and bounded
         */
        @AssertTrue(message = "mediaHls timeouts must be positive, ordered, and no greater than 60 seconds")
        public boolean isTimeoutsValid() {
            return isBoundedPositive(connectTimeout)
                    && isBoundedPositive(readTimeout)
                    && readTimeout.compareTo(connectTimeout) >= 0;
        }
    }

    /** Redis-backed fixed-window rate-limit settings. */
    public static class RateLimit {

        @Min(value = 1, message = "rateLimit.maxRequests must be positive")
        @Max(value = 10_000_000, message = "rateLimit.maxRequests must be bounded")
        private int maxRequests = 600;

        @Min(value = 1, message = "rateLimit.preAuthenticationMaxRequests must be positive")
        @Max(value = 10_000_000, message = "rateLimit.preAuthenticationMaxRequests must be bounded")
        private int preAuthenticationMaxRequests = 6_000;

        @NotNull(message = "rateLimit.window must be configured")
        private Duration window = Duration.ofMinutes(1);

        /** Secret-manager supplied HMAC key material used to protect Redis client-key digests. */
        @NotBlank(message = "rateLimit.keySecret must be supplied by secret management")
        private String keySecret;

        public int getMaxRequests() {
            return maxRequests;
        }

        public void setMaxRequests(int maxRequests) {
            this.maxRequests = maxRequests;
        }

        /**
         * Returns the higher address-based budget charged before authentication.
         *
         * @return pre-authentication address budget per window
         */
        public int getPreAuthenticationMaxRequests() {
            return preAuthenticationMaxRequests;
        }

        /**
         * Sets the address-based budget charged before authentication.
         *
         * @param preAuthenticationMaxRequests pre-authentication address budget per window
         */
        public void setPreAuthenticationMaxRequests(int preAuthenticationMaxRequests) {
            this.preAuthenticationMaxRequests = preAuthenticationMaxRequests;
        }

        /**
         * Alias for deployments that name the budget requests-per-window.
         *
         * @return maximum requests in one window
         */
        public int getRequestsPerWindow() {
            return maxRequests;
        }

        /**
         * Binds the common requests-per-window configuration spelling.
         *
         * @param requestsPerWindow maximum requests in one window
         */
        public void setRequestsPerWindow(int requestsPerWindow) {
            this.maxRequests = requestsPerWindow;
        }

        public Duration getWindow() {
            return window;
        }

        public void setWindow(Duration window) {
            this.window = window;
        }

        public String getKeySecret() {
            return keySecret;
        }

        public void setKeySecret(String keySecret) {
            this.keySecret = keySecret;
        }

        @AssertTrue(message = "rateLimit.window must be at least one millisecond and no greater than 24 hours")
        public boolean isWindowValid() {
            return window != null
                    && window.compareTo(Duration.ofMillis(1)) >= 0
                    && window.compareTo(Duration.ofHours(24)) <= 0;
        }

        /**
         * Validates that the pre-authentication address budget is not below the account budget.
         *
         * @return {@code true} when the pre-authentication budget is sufficient
         */
        @AssertTrue(message = "rateLimit.preAuthenticationMaxRequests must be at least maxRequests")
        public boolean isPreAuthenticationLimitValid() {
            return preAuthenticationMaxRequests >= maxRequests;
        }
    }

    /** Per-route upstream failure-isolation settings. */
    public static class Upstream {

        @Valid
        private final Bulkhead bulkhead = new Bulkhead();

        @Valid
        private final CircuitBreaker circuitBreaker = new CircuitBreaker();

        @Valid
        private final Retry retry = new Retry();

        public Bulkhead getBulkhead() {
            return bulkhead;
        }

        public CircuitBreaker getCircuitBreaker() {
            return circuitBreaker;
        }

        public Retry getRetry() {
            return retry;
        }
    }

    /** Non-waiting concurrency bound applied independently to each configured route. */
    public static class Bulkhead {

        @Min(value = 1, message = "upstream.bulkhead.maxConcurrentRequests must be positive")
        @Max(value = 4096, message = "upstream.bulkhead.maxConcurrentRequests must be bounded")
        private int maxConcurrentRequests = 64;

        public int getMaxConcurrentRequests() {
            return maxConcurrentRequests;
        }

        public void setMaxConcurrentRequests(int maxConcurrentRequests) {
            this.maxConcurrentRequests = maxConcurrentRequests;
        }
    }

    /** Consecutive-failure circuit-breaker settings applied independently to each route. */
    public static class CircuitBreaker {

        @Min(value = 1, message = "upstream.circuitBreaker.failureThreshold must be positive")
        @Max(value = 10_000, message = "upstream.circuitBreaker.failureThreshold must be bounded")
        private int failureThreshold = 5;

        @NotNull(message = "upstream.circuitBreaker.openDuration must be configured")
        private Duration openDuration = Duration.ofSeconds(10);

        public int getFailureThreshold() {
            return failureThreshold;
        }

        public void setFailureThreshold(int failureThreshold) {
            this.failureThreshold = failureThreshold;
        }

        public Duration getOpenDuration() {
            return openDuration;
        }

        public void setOpenDuration(Duration openDuration) {
            this.openDuration = openDuration;
        }

        @AssertTrue(message = "upstream.circuitBreaker.openDuration must be positive and no greater than 60 seconds")
        public boolean isOpenDurationValid() {
            return openDuration != null
                    && !openDuration.isZero()
                    && !openDuration.isNegative()
                    && openDuration.compareTo(Duration.ofSeconds(60)) <= 0;
        }
    }

    /**
     * Bounded automatic replay policy for demonstrably replay-safe upstream requests.
     *
     * <p>{@code maxAttempts} includes the initial call. The policy uses a capped exponential
     * full-jitter delay between attempts and reserves one fully bounded outbound call from
     * {@code totalTimeout} before it starts a retry.
     */
    public static class Retry {

        @Min(value = 1, message = "upstream.retry.maxAttempts must be positive")
        @Max(value = 5, message = "upstream.retry.maxAttempts must be no greater than 5")
        private int maxAttempts = 2;

        @NotNull(message = "upstream.retry.initialBackoff must be configured")
        private Duration initialBackoff = Duration.ofMillis(100);

        @NotNull(message = "upstream.retry.maxBackoff must be configured")
        private Duration maxBackoff = Duration.ofSeconds(1);

        @NotNull(message = "upstream.retry.totalTimeout must be configured")
        private Duration totalTimeout = Duration.ofSeconds(15);

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Duration getInitialBackoff() {
            return initialBackoff;
        }

        public void setInitialBackoff(Duration initialBackoff) {
            this.initialBackoff = initialBackoff;
        }

        public Duration getMaxBackoff() {
            return maxBackoff;
        }

        public void setMaxBackoff(Duration maxBackoff) {
            this.maxBackoff = maxBackoff;
        }

        public Duration getTotalTimeout() {
            return totalTimeout;
        }

        public void setTotalTimeout(Duration totalTimeout) {
            this.totalTimeout = totalTimeout;
        }

        /**
         * Ensures the exponential backoff envelope is finite and ordered.
         *
         * @return {@code true} when both delay bounds are positive, bounded, and ordered
         */
        @AssertTrue(message = "upstream.retry backoffs must be positive, ordered, and no greater than 60 seconds")
        public boolean isBackoffValid() {
            return isBoundedPositive(initialBackoff)
                    && isBoundedPositive(maxBackoff)
                    && initialBackoff.compareTo(maxBackoff) <= 0;
        }

        /**
         * Ensures the logical retry deadline is finite.
         *
         * @return {@code true} when the total timeout is positive and bounded
         */
        @AssertTrue(message = "upstream.retry.totalTimeout must be positive and no greater than 60 seconds")
        public boolean isTotalTimeoutValid() {
            return isBoundedPositive(totalTimeout);
        }
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
         * Whether the route requires a valid authenticated subject before forwarding.
         *
         * <p>Routes are protected by default. Public identity bootstrap endpoints must opt out
         * explicitly in deployment configuration so a newly added route cannot accidentally
         * expose user data without authentication.
         */
        private boolean authenticationRequired = true;

        /**
         * Whether this is the sole explicitly supported byte-streaming route.
         *
         * <p>Normal routes are always buffered. Configuration validation and {@link GatewayRoute}
         * independently restrict a streaming route to the exact authenticated notification SSE
         * operation, so a later route addition cannot accidentally turn the generic proxy into a
         * streaming tunnel.
         */
        private boolean streaming;

        /**
         * Whether this route owns the sole explicitly supported request-streaming operation.
         *
         * <p>The setting is intentionally narrower than {@link #streaming}: it permits only the
         * exact authenticated Document Vault multipart create path. Descendant reads and metadata
         * mutations remain on the ordinary bounded buffered proxy path.
         */
        private boolean documentUploadStreaming;

        /**
         * Whether this route owns the exact Media source-upload request-streaming exception.
         *
         * <p>The setting permits only canonical asset-ID {@code PUT .../source} requests on the
         * Media assets prefix. Metadata and session APIs never inherit request streaming.
         */
        private boolean mediaUploadStreaming;

        /**
         * Whether this route owns the exact Media HLS response-streaming exceptions.
         *
         * <p>The setting permits only validated asset manifest and segment {@code GET} requests
         * on the Media assets prefix. It does not enable generic prefix response streaming.
         */
        private boolean mediaHlsStreaming;

        /**
         * Optional HTTP methods to protect for a mixed public/protected route. An empty set means
         * every supported method is protected when {@link #authenticationRequired} is enabled.
         */
        private Set<String> authenticationRequiredMethods = Set.of();

        /**
         * Exact request paths that may use the configured public methods while the route remains
         * protected by default. This is intended for narrow bootstrap operations such as account
         * registration, not for descendant resources.
         */
        private Set<String> authenticationPublicPaths = Set.of();

        /**
         * HTTP methods that are public only when the request path is one of the exact public paths.
         */
        private Set<String> authenticationPublicMethods = Set.of();

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

        /**
         * Creates a route definition with an explicit authentication policy.
         *
         * @param id route identifier
         * @param pathPrefix public path prefix
         * @param upstream fixed upstream origin
         * @param authenticationRequired whether a valid bearer subject is required
         */
        public Route(String id, String pathPrefix, String upstream, boolean authenticationRequired) {
            this(id, pathPrefix, upstream);
            this.authenticationRequired = authenticationRequired;
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
         * Returns whether this route requires gateway authentication.
         *
         * @return {@code true} when a valid bearer subject is required
         */
        public boolean isAuthenticationRequired() {
            return authenticationRequired;
        }

        /**
         * Sets the route authentication policy during configuration binding.
         *
         * @param authenticationRequired whether a valid bearer subject is required
         */
        public void setAuthenticationRequired(boolean authenticationRequired) {
            this.authenticationRequired = authenticationRequired;
        }

        /**
         * Returns whether this route is the explicit SSE byte-streaming exception.
         *
         * @return whether the route may use the streaming forwarder
         */
        public boolean isStreaming() {
            return streaming;
        }

        /**
         * Sets whether the route is the explicit SSE byte-streaming exception.
         *
         * @param streaming whether the route uses the streaming forwarder
         */
        public void setStreaming(boolean streaming) {
            this.streaming = streaming;
        }

        /**
         * Returns whether this route owns the exact Document Vault request-streaming exception.
         *
         * @return whether the route may relay the document-create request body incrementally
         */
        public boolean isDocumentUploadStreaming() {
            return documentUploadStreaming;
        }

        /**
         * Sets whether this route owns the exact Document Vault request-streaming exception.
         *
         * @param documentUploadStreaming whether the route may relay the create body incrementally
         */
        public void setDocumentUploadStreaming(boolean documentUploadStreaming) {
            this.documentUploadStreaming = documentUploadStreaming;
        }

        /**
         * Returns whether this route owns the exact Media source-upload streaming exception.
         *
         * @return whether exact Media asset-source uploads may relay incrementally
         */
        public boolean isMediaUploadStreaming() {
            return mediaUploadStreaming;
        }

        /**
         * Sets whether this route owns the exact Media source-upload streaming exception.
         *
         * @param mediaUploadStreaming whether exact Media asset-source uploads may relay incrementally
         */
        public void setMediaUploadStreaming(boolean mediaUploadStreaming) {
            this.mediaUploadStreaming = mediaUploadStreaming;
        }

        /**
         * Returns whether this route owns the exact Media HLS response-streaming exceptions.
         *
         * @return whether exact Media HLS responses may relay incrementally
         */
        public boolean isMediaHlsStreaming() {
            return mediaHlsStreaming;
        }

        /**
         * Sets whether this route owns the exact Media HLS response-streaming exceptions.
         *
         * @param mediaHlsStreaming whether exact Media HLS responses may relay incrementally
         */
        public void setMediaHlsStreaming(boolean mediaHlsStreaming) {
            this.mediaHlsStreaming = mediaHlsStreaming;
        }

        /**
         * Returns the protected-method allow-list for a mixed route.
         *
         * @return protected HTTP methods, or an empty set for all methods
         */
        public Set<String> getAuthenticationRequiredMethods() {
            return Set.copyOf(authenticationRequiredMethods);
        }

        /**
         * Sets the protected-method allow-list during configuration binding.
         *
         * @param authenticationRequiredMethods protected HTTP methods
         */
        public void setAuthenticationRequiredMethods(Set<String> authenticationRequiredMethods) {
            this.authenticationRequiredMethods = authenticationRequiredMethods == null
                    ? Set.of()
                    : Set.copyOf(authenticationRequiredMethods);
        }

        /**
         * Returns exact request paths for public method exceptions on this route.
         *
         * @return exact public request paths
         */
        public Set<String> getAuthenticationPublicPaths() {
            return Set.copyOf(authenticationPublicPaths);
        }

        /**
         * Sets exact request paths for public method exceptions during configuration binding.
         *
         * @param authenticationPublicPaths exact public request paths
         */
        public void setAuthenticationPublicPaths(Set<String> authenticationPublicPaths) {
            this.authenticationPublicPaths = authenticationPublicPaths == null
                    ? Set.of()
                    : Set.copyOf(authenticationPublicPaths);
        }

        /**
         * Returns methods that are public only on the configured exact public paths.
         *
         * @return exact public methods
         */
        public Set<String> getAuthenticationPublicMethods() {
            return Set.copyOf(authenticationPublicMethods);
        }

        /**
         * Sets methods that are public only on the configured exact public paths.
         *
         * @param authenticationPublicMethods exact public methods
         */
        public void setAuthenticationPublicMethods(Set<String> authenticationPublicMethods) {
            this.authenticationPublicMethods = authenticationPublicMethods == null
                    ? Set.of()
                    : Set.copyOf(authenticationPublicMethods);
        }

        /**
         * Rejects malformed method names so authentication policy cannot be silently broadened by
         * a configuration typo.
         *
         * @return whether every configured method is a supported HTTP method name
         */
        @AssertTrue(message = "route authenticationRequiredMethods must contain valid HTTP methods")
        public boolean areAuthenticationRequiredMethodsValid() {
            return authenticationRequiredMethods.stream()
                    .allMatch(method -> method != null && SUPPORTED_GATEWAY_METHODS.contains(method));
        }

        /**
         * Rejects malformed methods used by exact public operation exceptions.
         *
         * @return whether every configured public method is supported
         */
        @AssertTrue(message = "route authenticationPublicMethods must contain valid HTTP methods")
        public boolean areAuthenticationPublicMethodsValid() {
            boolean supportedMethods = authenticationPublicMethods.stream()
                    .allMatch(method -> method != null && SUPPORTED_GATEWAY_METHODS.contains(method));
            return supportedMethods
                    && (authenticationRequiredMethods.isEmpty()
                            || authenticationRequiredMethods.containsAll(authenticationPublicMethods));
        }

        /**
         * Rejects malformed exact public operation paths.
         *
         * @return whether every configured public path is a valid absolute path
         */
        @AssertTrue(message = "route authenticationPublicPaths must contain valid exact paths")
        public boolean areAuthenticationPublicPathsValid() {
            return authenticationPublicPaths.stream().allMatch(GatewayRoute::isValidPathPrefix);
        }

        /**
         * Rejects wildcard, query-bearing, unversioned, or catch-all prefixes so route matching
         * remains deterministic and cannot be expanded by configuration typos.
         *
         * @return {@code true} when the path prefix is a valid named versioned public prefix
         */
        @AssertTrue(message = "route pathPrefix must be a named /api/v<positive-integer>/<resource> path without wildcards or a trailing slash")
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

        /**
         * Keeps the Assistant public prefix protected and on its bounded buffered path.
         *
         * <p>The forwarder assigns this fixed prefix a dedicated upstream client and disables
         * gateway retries. Method or public exceptions could otherwise turn part of the Assistant
         * API into an unauthenticated dependency call, so they fail deployment validation.
         *
         * @return whether an Assistant route has the reviewed authentication policy
         */
        @AssertTrue(message = "AI assistant routes must be authenticated without method, public, or streaming exceptions")
        public boolean isAiAssistantRouteConfigurationValid() {
            return !GatewayRoute.AI_ASSISTANT_PATH_PREFIX.equals(pathPrefix)
                    || (authenticationRequired
                            && authenticationRequiredMethods.isEmpty()
                            && authenticationPublicPaths.isEmpty()
                            && authenticationPublicMethods.isEmpty()
                            && !streaming
                            && !documentUploadStreaming
                            && !mediaUploadStreaming
                            && !mediaHlsStreaming);
        }

        /**
         * Prevents the generic route table from acquiring arbitrary streaming capabilities.
         *
         * @return whether a streaming route has exactly the narrow authenticated SSE policy
         */
        @AssertTrue(message = "streaming is allowed only for authenticated GET /api/v1/notifications/stream without public exceptions")
        public boolean isStreamingConfigurationValid() {
            return !streaming
                    || (GatewayRoute.NOTIFICATION_STREAM_PATH.equals(pathPrefix)
                            && authenticationRequired
                            && authenticationRequiredMethods.equals(Set.of("GET"))
                            && authenticationPublicPaths.isEmpty()
                            && authenticationPublicMethods.isEmpty());
        }

        /**
         * Prevents the generic route table from acquiring arbitrary request-streaming capability.
         *
         * <p>The Document Vault prefix carries both small buffered document operations and one
         * multipart create. The forwarder independently checks the exact POST method and path
         * before it bypasses the one-mebibyte ordinary request buffer.
         *
         * @return whether request streaming has the exact authenticated Document Vault policy
         */
        @AssertTrue(message = "documentUploadStreaming is allowed only for authenticated Document Vault routes without method or public exceptions")
        public boolean isDocumentUploadStreamingConfigurationValid() {
            return !documentUploadStreaming
                    || (GatewayRoute.DOCUMENT_UPLOAD_PATH.equals(pathPrefix)
                            && authenticationRequired
                            && authenticationRequiredMethods.isEmpty()
                            && authenticationPublicPaths.isEmpty()
                            && authenticationPublicMethods.isEmpty()
                            && !streaming);
        }

        /**
         * Prevents a generic Media prefix from acquiring arbitrary request-streaming capability.
         *
         * @return whether Media upload streaming has the exact authenticated policy
         */
        @AssertTrue(message = "mediaUploadStreaming is allowed only for authenticated Media assets routes without method or public exceptions")
        public boolean isMediaUploadStreamingConfigurationValid() {
            return !mediaUploadStreaming
                    || (GatewayRoute.MEDIA_ASSETS_PATH_PREFIX.equals(pathPrefix)
                            && authenticationRequired
                            && authenticationRequiredMethods.isEmpty()
                            && authenticationPublicPaths.isEmpty()
                            && authenticationPublicMethods.isEmpty()
                            && !streaming
                            && !documentUploadStreaming);
        }

        /**
         * Prevents a generic Media prefix from acquiring arbitrary response-streaming capability.
         *
         * @return whether Media HLS streaming has the exact authenticated policy
         */
        @AssertTrue(message = "mediaHlsStreaming is allowed only for authenticated Media assets routes without method or public exceptions")
        public boolean isMediaHlsStreamingConfigurationValid() {
            return !mediaHlsStreaming
                    || (GatewayRoute.MEDIA_ASSETS_PATH_PREFIX.equals(pathPrefix)
                            && authenticationRequired
                            && authenticationRequiredMethods.isEmpty()
                            && authenticationPublicPaths.isEmpty()
                            && authenticationPublicMethods.isEmpty()
                            && !streaming
                            && !documentUploadStreaming);
        }
    }
}
