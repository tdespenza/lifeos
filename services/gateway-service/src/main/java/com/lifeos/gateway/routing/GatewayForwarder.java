package com.lifeos.gateway.routing;

import com.lifeos.gateway.auth.GatewayAuthenticatedSubject;
import com.lifeos.gateway.config.GatewayProperties;
import com.lifeos.gateway.observability.CorrelationIdSupport;
import com.lifeos.gateway.observability.RequestContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.StreamingHttpOutputMessage;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Proxies an allow-listed request while preserving its public HTTP contract.
 *
 * <p>All ordinary routes buffer only bounded request and response bodies. There are narrowly
 * configured exceptions: notification SSE relays a response through fixed-size chunks under
 * separate connection admission and lifetime limits; Document Vault creation relays its multipart
 * request through a fixed-size buffer under independent upload admission; and Media exposes only
 * exact source-upload and HLS read relay operations under their own byte, timeout, and admission
 * bounds. No exception grants generic streaming capability to a path prefix. Hop-by-hop headers and
 * caller-supplied routing headers are removed, while the validated correlation ID is installed
 * exactly once on the downstream request.
 *
 * <p>{@code readBounded} consumes at most the configured limit plus one fixed-size read buffer,
 * runs in O(n) time for n bytes consumed, and uses O(limit) space per request. Inbound body buffers
 * are independently bounded by gateway-wide semaphores and aggregate byte budgets. At startup,
 * {@link GatewayProperties#isResponseBufferBudgetValid()} rejects a response-buffer count and
 * per-body-size combination that exceeds the aggregate budget. The response-buffer admission is
 * held through the client write, so the semaphore enforces that validated worst-case footprint,
 * independent of the upstream route bulkhead.
 */
@Component
public class GatewayForwarder {

    private static final Logger LOGGER = LoggerFactory.getLogger(GatewayForwarder.class);
    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade",
            "host",
            "content-length",
            "x-correlation-id",
            "x-forwarded-for",
            "x-forwarded-host",
            "x-forwarded-proto",
            "forwarded",
            "x-real-ip",
            "x-http-method-override",
            "x-method-override",
            "x-original-url",
            "x-rewrite-url",
            "x-lifeos-authenticated-account-id",
            "x-lifeos-authenticated-session-id",
            "x-lifeos-authentication-method",
            "x-lifeos-gateway-proof",
            "x-lifeos-workload-identity",
            "x-lifeos-workload-token");

    private final RestClient restClient;
    private final RestClient streamingRestClient;
    private final RestClient documentUploadRestClient;
    private final RestClient mediaUploadRestClient;
    private final RestClient mediaHlsRestClient;
    private final RestClient aiAssistantRestClient;
    private final GatewayProperties properties;
    private final GatewayUpstreamResilience resilience;
    private final GatewayRetryPolicy retryPolicy;
    private final MeterRegistry meterRegistry;
    private final Semaphore requestBodyAdmission;
    private final Counter requestBodyCapacityRejections;
    private final Semaphore responseBufferAdmission;
    private final Counter responseBufferCapacityRejections;
    private final Semaphore streamingAdmission;
    private final Counter streamingCapacityRejections;
    private final Semaphore documentUploadAdmission;
    private final Counter documentUploadCapacityRejections;
    private final Semaphore mediaUploadAdmission;
    private final Counter mediaUploadCapacityRejections;
    private final Semaphore mediaHlsAdmission;
    private final Counter mediaHlsCapacityRejections;
    private final Counter mediaHlsResponseLimitViolations;
    private final AtomicInteger inFlightRequests = new AtomicInteger();
    private final AtomicInteger inFlightStreams = new AtomicInteger();
    private final AtomicInteger inFlightDocumentUploads = new AtomicInteger();
    private final AtomicInteger inFlightMediaUploads = new AtomicInteger();
    private final AtomicInteger inFlightMediaHlsStreams = new AtomicInteger();
    private final ConcurrentMap<RetryMetricKey, Counter> retryCounters = new ConcurrentHashMap<>();

    /**
     * Creates a forwarder with the configured outbound HTTP client.
     *
     * @param restClient outbound HTTP client
     * @param properties gateway bounds
     * @param meterRegistry metrics registry for bounded in-flight request instrumentation
     * @throws IllegalArgumentException when response-buffer bounds exceed their aggregate budget
     */
    @Autowired
    public GatewayForwarder(
            @Qualifier("gatewayBufferedRestClient") RestClient restClient,
            @Qualifier("gatewayStreamingRestClient") RestClient streamingRestClient,
            @Qualifier("gatewayDocumentUploadRestClient") RestClient documentUploadRestClient,
            @Qualifier("gatewayMediaUploadRestClient") RestClient mediaUploadRestClient,
            @Qualifier("gatewayMediaHlsRestClient") RestClient mediaHlsRestClient,
            @Qualifier("gatewayAiAssistantRestClient") RestClient aiAssistantRestClient,
            GatewayProperties properties,
            MeterRegistry meterRegistry,
            GatewayUpstreamResilience resilience,
            GatewayRetryPolicy retryPolicy) {
        requireValidResponseBufferBudget(properties);
        requireValidRetryConfiguration(properties);
        requireValidDocumentUploadConfiguration(properties);
        requireValidMediaUploadConfiguration(properties);
        requireValidMediaHlsConfiguration(properties);
        this.restClient = restClient;
        this.streamingRestClient = streamingRestClient;
        this.documentUploadRestClient = documentUploadRestClient;
        this.mediaUploadRestClient = mediaUploadRestClient;
        this.mediaHlsRestClient = mediaHlsRestClient;
        this.aiAssistantRestClient = aiAssistantRestClient;
        this.properties = properties;
        this.resilience = resilience;
        this.retryPolicy = retryPolicy;
        this.meterRegistry = meterRegistry;
        this.requestBodyAdmission = new Semaphore(properties.getMaxConcurrentRequestBodyBuffers(), true);
        this.requestBodyCapacityRejections = Counter.builder("gateway.request.body.capacity.rejections")
                .description("Requests rejected because bounded inbound body buffering is full")
                .register(meterRegistry);
        Gauge.builder(
                        "gateway.request.body.available.permits",
                        requestBodyAdmission,
                        Semaphore::availablePermits)
                .description("Available inbound request-body buffer permits")
                .register(meterRegistry);
        this.responseBufferAdmission = new Semaphore(properties.getMaxConcurrentResponseBuffers(), true);
        this.responseBufferCapacityRejections = Counter.builder("gateway.response.buffer.capacity.rejections")
                .description("Requests rejected because bounded response buffering is full")
                .register(meterRegistry);
        Gauge.builder(
                        "gateway.response.buffer.available.permits",
                        responseBufferAdmission,
                        Semaphore::availablePermits)
                .description("Available downstream response-buffer permits")
                .register(meterRegistry);
        this.streamingAdmission = new Semaphore(
                properties.getStreaming().getMaxConcurrentConnections(), true);
        this.streamingCapacityRejections = Counter.builder("gateway.streaming.capacity.rejections")
                .description("Requests rejected because bounded SSE connection admission is full")
                .register(meterRegistry);
        Gauge.builder(
                        "gateway.streaming.available.permits",
                        streamingAdmission,
                        Semaphore::availablePermits)
                .description("Available live SSE connection permits")
                .register(meterRegistry);
        meterRegistry.gauge("gateway.inflight.requests", inFlightRequests);
        meterRegistry.gauge("gateway.streaming.inflight.connections", inFlightStreams);
        this.documentUploadAdmission = new Semaphore(
                properties.getDocumentUpload().getMaxConcurrentUploads(), true);
        this.documentUploadCapacityRejections = Counter.builder("gateway.document.upload.capacity.rejections")
                .description("Requests rejected because bounded Document Vault upload admission is full")
                .register(meterRegistry);
        Gauge.builder(
                        "gateway.document.upload.available.permits",
                        documentUploadAdmission,
                        Semaphore::availablePermits)
                .description("Available Document Vault request-streaming permits")
                .register(meterRegistry);
        meterRegistry.gauge("gateway.document.upload.inflight", inFlightDocumentUploads);
        this.mediaUploadAdmission = new Semaphore(
                properties.getMediaUpload().getMaxConcurrentUploads(), true);
        this.mediaUploadCapacityRejections = Counter.builder("gateway.media.upload.capacity.rejections")
                .description("Requests rejected because bounded Media source-upload admission is full")
                .register(meterRegistry);
        Gauge.builder(
                        "gateway.media.upload.available.permits",
                        mediaUploadAdmission,
                        Semaphore::availablePermits)
                .description("Available Media source-upload request-streaming permits")
                .register(meterRegistry);
        meterRegistry.gauge("gateway.media.upload.inflight", inFlightMediaUploads);
        this.mediaHlsAdmission = new Semaphore(
                properties.getMediaHls().getMaxConcurrentStreams(), true);
        this.mediaHlsCapacityRejections = Counter.builder("gateway.media.hls.capacity.rejections")
                .description("Requests rejected because bounded Media HLS response-stream admission is full")
                .register(meterRegistry);
        this.mediaHlsResponseLimitViolations = Counter.builder("gateway.media.hls.response.limit.violations")
                .description("Media HLS upstream responses exceeding the reviewed relay byte bound")
                .register(meterRegistry);
        Gauge.builder(
                        "gateway.media.hls.available.permits",
                        mediaHlsAdmission,
                        Semaphore::availablePermits)
                .description("Available Media HLS response-stream permits")
                .register(meterRegistry);
        meterRegistry.gauge("gateway.media.hls.inflight", inFlightMediaHlsStreams);
    }

    /**
     * Creates a forwarder with its own in-process route resilience. Test-only: production wiring
     * must use the injected shared {@link GatewayUpstreamResilience}.
     */
    GatewayForwarder(RestClient restClient, GatewayProperties properties, MeterRegistry meterRegistry) {
        this(
                restClient,
                restClient,
                restClient,
                restClient,
                restClient,
                restClient,
                properties,
                meterRegistry,
                new GatewayUpstreamResilience(properties, meterRegistry),
                new GatewayRetryPolicy(properties));
    }

    /**
     * Forwards one request to its fixed upstream route and writes the raw upstream response.
     *
     * @param request inbound request
     * @param response inbound response
     * @param route resolved fixed route
     * @param correlationId validated request correlation ID
     * @throws IOException when servlet request/response I/O fails
     */
    public void forward(
            HttpServletRequest request,
            HttpServletResponse response,
            GatewayRoute route,
            String correlationId)
            throws IOException {
        forward(request, response, route, correlationId, null);
    }

    /**
     * Forwards one request and installs the gateway-validated subject context when protected.
     *
     * @param request inbound request
     * @param response inbound response
     * @param route resolved fixed route
     * @param correlationId validated request correlation ID
     * @param subject gateway-validated subject, or {@code null} for a public route
     * @throws IOException when servlet request/response I/O fails
     */
    public void forward(
            HttpServletRequest request,
            HttpServletResponse response,
            GatewayRoute route,
            String correlationId,
            GatewayAuthenticatedSubject subject)
            throws IOException {
        if (route.streaming()) {
            forwardStreaming(request, response, route, correlationId, subject);
            return;
        }
        String requestPath = pathWithoutContext(request);
        if (route.isExactMediaHlsRequest(requestPath, request.getMethod())) {
            rejectMediaHlsRequestBodyFraming(request);
            forwardMediaHls(request, response, route, correlationId, subject);
            return;
        }
        if (route.isExactDocumentUploadRequest(requestPath, request.getMethod())) {
            forwardDocumentUpload(request, response, route, correlationId, subject);
            return;
        }
        if (route.isExactMediaUploadRequest(requestPath, request.getMethod())) {
            forwardMediaUpload(request, response, route, correlationId, subject);
            return;
        }
        HttpMethod method = HttpMethod.valueOf(request.getMethod());
        boolean bodyAdmissionAcquired = acquireRequestBodyAdmission(request, method);
        boolean responseBufferAdmissionAcquired = false;
        try {
            byte[] requestBody = readRequestBody(request, method);
            URI target = targetUri(route, request);

            responseBufferAdmissionAcquired = acquireResponseBufferAdmission();
            GatewayUpstreamResilience.Permit permit = resilience.acquire(route);
            DownstreamResponse downstream = null;
            try (permit) {
                inFlightRequests.incrementAndGet();
                try {
                    downstream = forwardWithRetries(
                    request, method, target, requestBody, correlationId, subject, route);
                    if (downstream.status().is5xxServerError()) {
                        permit.recordFailure();
                    } else {
                        permit.recordSuccess();
                    }
                } catch (GatewayUpstreamException exception) {
                    permit.recordFailure();
                    logUpstreamFailure(route, exception.getStatus(), exception.getFailureClass());
                    throw exception;
                } catch (GatewayPayloadTooLargeException exception) {
                    permit.recordFailure();
                    logUpstreamFailure(route, HttpStatus.BAD_GATEWAY, "oversized-response");
                    throw new GatewayUpstreamException(HttpStatus.BAD_GATEWAY, exception);
                } catch (ResourceAccessException exception) {
                    permit.recordFailure();
                    boolean timeout = isTimeout(exception);
                    HttpStatus status = timeout ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY;
                    logUpstreamFailure(route, status, timeout ? "timeout" : "transport");
                    throw new GatewayUpstreamException(status, exception);
                } catch (RestClientException exception) {
                    permit.recordFailure();
                    logUpstreamFailure(route, HttpStatus.BAD_GATEWAY, "client");
                    throw new GatewayUpstreamException(HttpStatus.BAD_GATEWAY, exception);
                } finally {
                    inFlightRequests.decrementAndGet();
                }
            }
            releaseRequestBodyAdmission(bodyAdmissionAcquired);
            bodyAdmissionAcquired = false;
            writeResponse(response, downstream, method);
        } finally {
            releaseResponseBufferAdmission(responseBufferAdmissionAcquired);
            releaseRequestBodyAdmission(bodyAdmissionAcquired);
        }
    }

    /**
     * Relays the exact Document Vault multipart create request through a fixed-size buffer.
     *
     * <p>The controller has already authenticated and rate-limited this request. Unlike ordinary
     * proxy writes, the body is copied from the servlet input stream to the outbound request as it
     * is read, so a valid ten-mebibyte document never enters a gateway byte array. This path is
     * deliberately non-retryable: an interrupted multipart write has unknown upstream effects,
     * and Document Vault owns its durable idempotency contract. The upload admission and the route
     * bulkhead are both non-waiting, which bounds virtual-thread and socket coupling under slow
     * client uploads.
     */
    private void forwardDocumentUpload(
            HttpServletRequest request,
            HttpServletResponse response,
            GatewayRoute route,
            String correlationId,
            GatewayAuthenticatedSubject subject)
            throws IOException {
        GatewayProperties.DocumentUpload upload = properties.getDocumentUpload();
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > upload.getMaxRequestBodyBytes()) {
            throw new GatewayPayloadTooLargeException();
        }
        if (!documentUploadAdmission.tryAcquire()) {
            documentUploadCapacityRejections.increment();
            throw new GatewayDocumentUploadCapacityException();
        }

        boolean responseBufferAdmissionAcquired = false;
        try {
            responseBufferAdmissionAcquired = acquireResponseBufferAdmission();
            GatewayUpstreamResilience.Permit permit;
            try {
                permit = resilience.acquire(route);
            } catch (GatewayUpstreamException exception) {
                logUpstreamFailure(route, exception.getStatus(), exception.getFailureClass());
                throw exception;
            }

            DownstreamResponse downstream;
            try (permit) {
                inFlightRequests.incrementAndGet();
                inFlightDocumentUploads.incrementAndGet();
                try {
                    downstream = invokeDocumentUpload(request, route, correlationId, subject, upload);
                    if (downstream.status().is5xxServerError()) {
                        permit.recordFailure();
                    } else {
                        permit.recordSuccess();
                    }
                } catch (GatewayPayloadTooLargeException exception) {
                    // A lying or absent Content-Length can be discovered only while copying. It
                    // is a client validation result, not evidence that the upstream is unhealthy.
                    permit.recordAbandoned();
                    throw exception;
                } catch (ResourceAccessException exception) {
                    if (hasPayloadTooLargeCause(exception)) {
                        permit.recordAbandoned();
                        throw new GatewayPayloadTooLargeException();
                    }
                    if (hasClientRequestAbortCause(exception)) {
                        permit.recordAbandoned();
                        throw new GatewayBadRequestException(exception);
                    }
                    permit.recordFailure();
                    boolean timeout = isTimeout(exception);
                    HttpStatus status = timeout ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY;
                    logUpstreamFailure(route, status, timeout ? "upload-timeout" : "upload-transport");
                    throw new GatewayUpstreamException(status, exception);
                } catch (RestClientException exception) {
                    if (hasPayloadTooLargeCause(exception)) {
                        permit.recordAbandoned();
                        throw new GatewayPayloadTooLargeException();
                    }
                    if (hasClientRequestAbortCause(exception)) {
                        permit.recordAbandoned();
                        throw new GatewayBadRequestException(exception);
                    }
                    permit.recordFailure();
                    logUpstreamFailure(route, HttpStatus.BAD_GATEWAY, "upload-client");
                    throw new GatewayUpstreamException(HttpStatus.BAD_GATEWAY, exception);
                } finally {
                    inFlightDocumentUploads.decrementAndGet();
                    inFlightRequests.decrementAndGet();
                }
            }
            writeResponse(response, downstream, HttpMethod.POST);
        } finally {
            releaseResponseBufferAdmission(responseBufferAdmissionAcquired);
            documentUploadAdmission.release();
        }
    }

    private DownstreamResponse invokeDocumentUpload(
            HttpServletRequest request,
            GatewayRoute route,
            String correlationId,
            GatewayAuthenticatedSubject subject,
            GatewayProperties.DocumentUpload upload) {
        RestClient.RequestBodySpec requestSpec = documentUploadRestClient.post().uri(targetUri(route, request));
        requestSpec.headers(headers -> copyRequestHeaders(request, headers, correlationId, subject));
        long declaredLength = request.getContentLengthLong();
        if (declaredLength >= 0) {
            requestSpec.contentLength(declaredLength);
        }
        return requestSpec.body((StreamingHttpOutputMessage.Body) output -> copyBounded(
                        request, output, upload.getMaxRequestBodyBytes()))
                .exchange((clientRequest, clientResponse) -> readResponse(clientResponse));
    }

    /**
     * Relays the exact Media asset-source multipart upload through a fixed-size buffer.
     *
     * <p>Media's reviewed 51 MiB request bound and 60-second service deadline are isolated from
     * Document Vault and ordinary JSON forwarding. The request is never automatically replayed:
     * Media owns its durable idempotency and version transition. Its virtual route bulkhead and
     * circuit state prevent slow uploads from starving metadata/session requests to the same
     * service prefix.
     */
    private void forwardMediaUpload(
            HttpServletRequest request,
            HttpServletResponse response,
            GatewayRoute route,
            String correlationId,
            GatewayAuthenticatedSubject subject)
            throws IOException {
        GatewayProperties.MediaUpload upload = properties.getMediaUpload();
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > upload.getMaxRequestBodyBytes()) {
            throw new GatewayPayloadTooLargeException();
        }
        if (!mediaUploadAdmission.tryAcquire()) {
            mediaUploadCapacityRejections.increment();
            throw new GatewayMediaUploadCapacityException();
        }

        boolean responseBufferAdmissionAcquired = false;
        try {
            responseBufferAdmissionAcquired = acquireResponseBufferAdmission();
            GatewayUpstreamResilience.Permit permit;
            try {
                permit = resilience.acquireMediaUpload(route);
            } catch (GatewayUpstreamException exception) {
                logUpstreamFailure(route, exception.getStatus(), exception.getFailureClass());
                throw exception;
            }

            DownstreamResponse downstream;
            try (permit) {
                inFlightRequests.incrementAndGet();
                inFlightMediaUploads.incrementAndGet();
                try {
                    downstream = invokeMediaUpload(request, route, correlationId, subject, upload);
                    if (downstream.status().is5xxServerError()) {
                        permit.recordFailure();
                    } else {
                        permit.recordSuccess();
                    }
                } catch (GatewayPayloadTooLargeException exception) {
                    // A dishonest/chunked client body is a client validation result, not a Media
                    // dependency health signal. A half-open probe must remain available to a real
                    // upstream call.
                    permit.recordAbandoned();
                    throw exception;
                } catch (ResourceAccessException exception) {
                    if (hasPayloadTooLargeCause(exception)) {
                        permit.recordAbandoned();
                        throw new GatewayPayloadTooLargeException();
                    }
                    if (hasClientRequestAbortCause(exception)) {
                        permit.recordAbandoned();
                        throw new GatewayBadRequestException(exception);
                    }
                    permit.recordFailure();
                    boolean timeout = isTimeout(exception);
                    HttpStatus status = timeout ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY;
                    logUpstreamFailure(route, status, timeout ? "media-upload-timeout" : "media-upload-transport");
                    throw new GatewayUpstreamException(status, exception);
                } catch (RestClientException exception) {
                    if (hasPayloadTooLargeCause(exception)) {
                        permit.recordAbandoned();
                        throw new GatewayPayloadTooLargeException();
                    }
                    if (hasClientRequestAbortCause(exception)) {
                        permit.recordAbandoned();
                        throw new GatewayBadRequestException(exception);
                    }
                    permit.recordFailure();
                    logUpstreamFailure(route, HttpStatus.BAD_GATEWAY, "media-upload-client");
                    throw new GatewayUpstreamException(HttpStatus.BAD_GATEWAY, exception);
                } finally {
                    inFlightMediaUploads.decrementAndGet();
                    inFlightRequests.decrementAndGet();
                }
            }
            writeResponse(response, downstream, HttpMethod.PUT);
        } finally {
            releaseResponseBufferAdmission(responseBufferAdmissionAcquired);
            mediaUploadAdmission.release();
        }
    }

    private DownstreamResponse invokeMediaUpload(
            HttpServletRequest request,
            GatewayRoute route,
            String correlationId,
            GatewayAuthenticatedSubject subject,
            GatewayProperties.MediaUpload upload) {
        RestClient.RequestBodySpec requestSpec = mediaUploadRestClient.put().uri(targetUri(route, request));
        requestSpec.headers(headers -> copyRequestHeaders(request, headers, correlationId, subject));
        long declaredLength = request.getContentLengthLong();
        if (declaredLength >= 0) {
            requestSpec.contentLength(declaredLength);
        }
        return requestSpec.body((StreamingHttpOutputMessage.Body) output -> copyBounded(
                        request, output, upload.getMaxRequestBodyBytes()))
                .exchange((clientRequest, clientResponse) -> readResponse(clientResponse));
    }

    /**
     * Relays only exact Media HLS manifest and segment responses through a fixed-size buffer.
     *
     * <p>Media source uploads and HLS reads receive separate admission and virtual resilience
     * route state. No retry is attempted: a partial media response cannot be safely replayed to a
     * client, and HLS clients own any later manifest/segment retrieval.
     */
    private void forwardMediaHls(
            HttpServletRequest request,
            HttpServletResponse response,
            GatewayRoute route,
            String correlationId,
            GatewayAuthenticatedSubject subject)
            throws IOException {
        if (!mediaHlsAdmission.tryAcquire()) {
            mediaHlsCapacityRejections.increment();
            throw new GatewayMediaHlsCapacityException();
        }
        try {
            GatewayUpstreamResilience.Permit permit;
            try {
                permit = resilience.acquireMediaHls(route);
            } catch (GatewayUpstreamException exception) {
                logUpstreamFailure(route, exception.getStatus(), exception.getFailureClass());
                throw exception;
            }
            try (permit) {
                inFlightRequests.incrementAndGet();
                inFlightMediaHlsStreams.incrementAndGet();
                try {
                    HttpStatusCode status = invokeMediaHlsUpstream(
                            request, response, route, correlationId, subject);
                    if (status.is5xxServerError()) {
                        permit.recordFailure();
                    } else {
                        permit.recordSuccess();
                    }
                } catch (GatewayResponseTooLargeException exception) {
                    mediaHlsResponseLimitViolations.increment();
                    permit.recordFailure();
                    logUpstreamFailure(route, HttpStatus.BAD_GATEWAY, "media-hls-oversized-response");
                    if (!response.isCommitted()) {
                        throw new GatewayUpstreamException(HttpStatus.BAD_GATEWAY, exception);
                    }
                    // The first chunks can commit a content-length-free HTTP response before an
                    // unknown-length upstream exceeds its hard ceiling. Returning normally ends
                    // the servlet response; clients retry the manifest/segment rather than the
                    // gateway retaining bytes to manufacture a late error response.
                    LOGGER.warn(
                            "gateway Media HLS upstream exceeded response bound after response commit routeId={} correlationId={}",
                            route.id(),
                            RequestContext.CORRELATION_ID.isBound()
                                    ? RequestContext.CORRELATION_ID.get()
                                    : "unbound");
                } catch (GatewayUpstreamException exception) {
                    permit.recordFailure();
                    logUpstreamFailure(route, exception.getStatus(), exception.getFailureClass());
                    throw exception;
                } catch (ResourceAccessException exception) {
                    permit.recordFailure();
                    boolean timeout = isTimeout(exception);
                    HttpStatus status = timeout ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY;
                    logUpstreamFailure(route, status, timeout ? "media-hls-timeout" : "media-hls-transport");
                    throw new GatewayUpstreamException(status, exception);
                } catch (RestClientException exception) {
                    permit.recordFailure();
                    logUpstreamFailure(route, HttpStatus.BAD_GATEWAY, "media-hls-client");
                    throw new GatewayUpstreamException(HttpStatus.BAD_GATEWAY, exception);
                } catch (IOException exception) {
                    permit.recordFailure();
                    logUpstreamFailure(route, HttpStatus.BAD_GATEWAY, "media-hls-read");
                    if (!response.isCommitted()) {
                        throw new GatewayUpstreamException(HttpStatus.BAD_GATEWAY, exception);
                    }
                    LOGGER.debug(
                            "gateway Media HLS upstream stream ended after response commit routeId={} correlationId={}",
                            route.id(),
                            RequestContext.CORRELATION_ID.isBound()
                                    ? RequestContext.CORRELATION_ID.get()
                                    : "unbound");
                } finally {
                    inFlightMediaHlsStreams.decrementAndGet();
                    inFlightRequests.decrementAndGet();
                }
            }
        } finally {
            mediaHlsAdmission.release();
        }
    }

    private HttpStatusCode invokeMediaHlsUpstream(
            HttpServletRequest request,
            HttpServletResponse response,
            GatewayRoute route,
            String correlationId,
            GatewayAuthenticatedSubject subject)
            throws IOException {
        return mediaHlsRestClient.get()
                .uri(targetUri(route, request))
                .headers(headers -> copyRequestHeaders(request, headers, correlationId, subject))
                .exchange((clientRequest, clientResponse) -> writeMediaHlsResponse(
                        response, clientResponse, route, properties.getMediaHls().getMaxResponseBodyBytes()));
    }

    private static HttpStatusCode writeMediaHlsResponse(
            HttpServletResponse response,
            ClientHttpResponse upstream,
            GatewayRoute route,
            long maxResponseBodyBytes)
            throws IOException {
        long declaredLength = upstream.getHeaders().getContentLength();
        if (declaredLength > maxResponseBodyBytes) {
            throw new GatewayResponseTooLargeException();
        }
        HttpStatusCode status = upstream.getStatusCode();
        response.setStatus(status.value());
        copyResponseHeaders(response, upstream.getHeaders());

        try (InputStream input = upstream.getBody()) {
            OutputStream output = response.getOutputStream();
            byte[] buffer = new byte[16_384];
            long total = 0;
            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1) {
                total += bytesRead;
                if (total > maxResponseBodyBytes) {
                    throw new GatewayResponseTooLargeException();
                }
                try {
                    output.write(buffer, 0, bytesRead);
                } catch (IOException clientDisconnect) {
                    LOGGER.debug(
                            "gateway Media HLS downstream disconnected routeId={} correlationId={}",
                            route.id(),
                            RequestContext.CORRELATION_ID.isBound()
                                    ? RequestContext.CORRELATION_ID.get()
                                    : "unbound");
                    return status;
                }
            }
            try {
                output.flush();
            } catch (IOException clientDisconnect) {
                LOGGER.debug(
                        "gateway Media HLS downstream disconnected routeId={} correlationId={}",
                        route.id(),
                        RequestContext.CORRELATION_ID.isBound()
                                ? RequestContext.CORRELATION_ID.get()
                                : "unbound");
            }
        }
        return status;
    }

    /**
     * Relays the one permitted SSE response without retaining its body in memory.
     *
     * <p>The controller has already enforced the exact GET route, authenticated the subject,
     * charged rate-limit admission, and established the correlation context. This method has a
     * separate fair semaphore for live connections and uses the SSE-only client deadlines. It does
     * not retry a stream: replay belongs to the client and notification service through
     * {@code Last-Event-ID}. The upstream input stream is closed on every exit path, including a
     * downstream socket disconnect, which cancels the active upstream body consumption.
     */
    private void forwardStreaming(
            HttpServletRequest request,
            HttpServletResponse response,
            GatewayRoute route,
            String correlationId,
            GatewayAuthenticatedSubject subject)
            throws IOException {
        if (!streamingAdmission.tryAcquire()) {
            streamingCapacityRejections.increment();
            throw new GatewayStreamingCapacityException();
        }
        try {
            GatewayUpstreamResilience.Permit permit;
            try {
                permit = resilience.acquire(route);
            } catch (GatewayUpstreamException exception) {
                logUpstreamFailure(route, exception.getStatus(), exception.getFailureClass());
                throw exception;
            }
            try (permit) {
                inFlightRequests.incrementAndGet();
                inFlightStreams.incrementAndGet();
                try {
                    HttpStatusCode status = invokeStreamingUpstream(
                            request, response, route, correlationId, subject);
                    if (status.is5xxServerError()) {
                        permit.recordFailure();
                    } else {
                        permit.recordSuccess();
                    }
                } catch (GatewayUpstreamException exception) {
                    permit.recordFailure();
                    logUpstreamFailure(route, exception.getStatus(), exception.getFailureClass());
                    throw exception;
                } catch (ResourceAccessException exception) {
                    permit.recordFailure();
                    boolean timeout = isTimeout(exception);
                    HttpStatus status = timeout ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY;
                    logUpstreamFailure(route, status, timeout ? "timeout" : "transport");
                    throw new GatewayUpstreamException(status, exception);
                } catch (RestClientException exception) {
                    permit.recordFailure();
                    logUpstreamFailure(route, HttpStatus.BAD_GATEWAY, "client");
                    throw new GatewayUpstreamException(HttpStatus.BAD_GATEWAY, exception);
                } catch (IOException exception) {
                    permit.recordFailure();
                    logUpstreamFailure(route, HttpStatus.BAD_GATEWAY, "stream-read");
                    if (!response.isCommitted()) {
                        throw new GatewayUpstreamException(HttpStatus.BAD_GATEWAY, exception);
                    }
                    // An error after headers/body are committed cannot become an RFC 9457 response.
                    // The bounded stream ends; the client reconnects using Last-Event-ID.
                    LOGGER.debug(
                            "gateway SSE upstream stream ended after response commit routeId={} correlationId={}",
                            route.id(),
                            RequestContext.CORRELATION_ID.isBound()
                                    ? RequestContext.CORRELATION_ID.get()
                                    : "unbound");
                } finally {
                    inFlightStreams.decrementAndGet();
                    inFlightRequests.decrementAndGet();
                }
            }
        } finally {
            streamingAdmission.release();
        }
    }

    private HttpStatusCode invokeStreamingUpstream(
            HttpServletRequest request,
            HttpServletResponse response,
            GatewayRoute route,
            String correlationId,
            GatewayAuthenticatedSubject subject)
            throws IOException {
        URI target = targetUri(route, request);
        return streamingRestClient.get()
                .uri(target)
                .headers(headers -> copyRequestHeaders(request, headers, correlationId, subject))
                .exchange((clientRequest, clientResponse) -> writeStreamingResponse(response, clientResponse, route));
    }

    private static HttpStatusCode writeStreamingResponse(
            HttpServletResponse response, ClientHttpResponse upstream, GatewayRoute route) throws IOException {
        HttpStatusCode status = upstream.getStatusCode();
        response.setStatus(status.value());
        copyResponseHeaders(response, upstream.getHeaders());

        try (InputStream input = upstream.getBody()) {
            OutputStream output = response.getOutputStream();
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1) {
                try {
                    output.write(buffer, 0, bytesRead);
                    // SSE events must become visible promptly; this still retains only the fixed
                    // eight-kibibyte relay buffer rather than the generic response body.
                    output.flush();
                } catch (IOException clientDisconnect) {
                    LOGGER.debug(
                            "gateway SSE downstream disconnected routeId={} correlationId={}",
                            route.id(),
                            RequestContext.CORRELATION_ID.isBound()
                                    ? RequestContext.CORRELATION_ID.get()
                                    : "unbound");
                    return status;
                }
            }
        }
        return status;
    }

    private boolean acquireResponseBufferAdmission() {
        if (!responseBufferAdmission.tryAcquire()) {
            responseBufferCapacityRejections.increment();
            throw new GatewayResponseBufferCapacityException();
        }
        return true;
    }

    private static void requireValidResponseBufferBudget(GatewayProperties properties) {
        if (!properties.isResponseBufferBudgetValid()) {
            throw new IllegalArgumentException(
                    "response buffer count and size must fit maxResponseBufferBytes");
        }
    }

    private static void requireValidRetryConfiguration(GatewayProperties properties) {
        GatewayProperties.Retry retry = properties.getUpstream().getRetry();
        if (retry.getMaxAttempts() < 1
                || retry.getMaxAttempts() > 5
                || !retry.isBackoffValid()
                || !retry.isTotalTimeoutValid()
                || !properties.isRetryTimeoutBudgetValid()) {
            throw new IllegalArgumentException("gateway upstream retry configuration is invalid");
        }
    }

    private static void requireValidDocumentUploadConfiguration(GatewayProperties properties) {
        GatewayProperties.DocumentUpload upload = properties.getDocumentUpload();
        if (upload.getMaxConcurrentUploads() < 1
                || upload.getMaxConcurrentUploads() > 64
                || upload.getMaxRequestBodyBytes() < 1
                || upload.getMaxRequestBodyBytes()
                        > GatewayProperties.DocumentUpload.MAX_DOCUMENT_UPLOAD_BODY_BYTES
                || !upload.isTimeoutsValid()) {
            throw new IllegalArgumentException("gateway Document Vault upload configuration is invalid");
        }
    }

    private static void requireValidMediaUploadConfiguration(GatewayProperties properties) {
        GatewayProperties.MediaUpload upload = properties.getMediaUpload();
        if (upload.getMaxConcurrentUploads() < 1
                || upload.getMaxConcurrentUploads() > 32
                || upload.getMaxRequestBodyBytes() < 1
                || upload.getMaxRequestBodyBytes()
                        > GatewayProperties.MediaUpload.MAX_MEDIA_UPLOAD_BODY_BYTES
                || !upload.isTimeoutsValid()) {
            throw new IllegalArgumentException("gateway Media upload configuration is invalid");
        }
    }

    private static void requireValidMediaHlsConfiguration(GatewayProperties properties) {
        GatewayProperties.MediaHls hls = properties.getMediaHls();
        if (hls.getMaxConcurrentStreams() < 1
                || hls.getMaxConcurrentStreams() > 64
                || hls.getMaxResponseBodyBytes() < 1
                || hls.getMaxResponseBodyBytes()
                        > GatewayProperties.MediaHls.MAX_MEDIA_HLS_RESPONSE_BYTES
                || !hls.isTimeoutsValid()) {
            throw new IllegalArgumentException("gateway Media HLS configuration is invalid");
        }
    }

    private void releaseResponseBufferAdmission(boolean acquired) {
        if (acquired) {
            responseBufferAdmission.release();
        }
    }

    private boolean acquireRequestBodyAdmission(HttpServletRequest request, HttpMethod method) {
        long declaredLength = request.getContentLengthLong();
        if (declaredLength == 0 || !mayHaveBody(method)) {
            return false;
        }
        if (!requestBodyAdmission.tryAcquire()) {
            requestBodyCapacityRejections.increment();
            throw new GatewayRequestBodyCapacityException();
        }
        return true;
    }

    private void releaseRequestBodyAdmission(boolean acquired) {
        if (acquired) {
            requestBodyAdmission.release();
        }
    }

    /**
     * Executes one logical upstream invocation, including only safe automatic retries.
     *
     * <p>The route bulkhead permit is held by the caller for this entire loop. A retry therefore
     * cannot amplify concurrency or evade circuit admission. The circuit receives one final logical
     * success or failure outcome from the caller after this method returns or throws.
     */
    private DownstreamResponse forwardWithRetries(
            HttpServletRequest request,
            HttpMethod method,
            URI target,
            byte[] requestBody,
            String correlationId,
            GatewayAuthenticatedSubject subject,
            GatewayRoute route) {
        if (route.isAiAssistantRoute()) {
            // The gateway cannot prove that an Assistant GET is free of provider/tool work. In
            // addition, its dedicated client has a longer budget than generic retry accounting.
            // Relay the first classified response once; the service owns any safe retry policy.
            return invokeUpstream(request, method, target, requestBody, correlationId, subject, route);
        }
        long startedAtNanos = retryPolicy.start();
        int completedAttempts = 0;
        while (true) {
            try {
                DownstreamResponse downstream = invokeUpstream(
                        request, method, target, requestBody, correlationId, subject, route);
                completedAttempts++;
                if (!isTransientStatus(downstream.status())) {
                    return downstream;
                }

                GatewayRetryPolicy.RetryDecision decision =
                        retryPolicy.nextRetry(method, completedAttempts, startedAtNanos);
                if (!decision.retry()) {
                    recordRetrySkipped(route, decision.skipReason());
                    return downstream;
                }
                recordRetryAttempt(route, "upstream_status");
                if (!retryPolicy.await(decision.delay())) {
                    recordRetrySkipped(route, "interrupted");
                    return downstream;
                }
                // Drop the bounded response before starting another attempt so one route permit
                // never retains multiple maximum-sized response bodies at the same time.
                downstream = null;
            } catch (ResourceAccessException exception) {
                completedAttempts++;
                GatewayRetryPolicy.RetryDecision decision =
                        retryPolicy.nextRetry(method, completedAttempts, startedAtNanos);
                if (!decision.retry()) {
                    recordRetrySkipped(route, decision.skipReason());
                    throw exception;
                }
                recordRetryAttempt(route, "transport");
                if (!retryPolicy.await(decision.delay())) {
                    recordRetrySkipped(route, "interrupted");
                    throw exception;
                }
            }
        }
    }

    private DownstreamResponse invokeUpstream(
            HttpServletRequest request,
            HttpMethod method,
            URI target,
            byte[] requestBody,
            String correlationId,
            GatewayAuthenticatedSubject subject,
            GatewayRoute route) {
        RestClient client = route.isAiAssistantRoute() ? aiAssistantRestClient : restClient;
        RestClient.RequestBodySpec requestSpec = client.method(method).uri(target);
        requestSpec.headers(headers -> copyRequestHeaders(request, headers, correlationId, subject));
        if (requestBody.length > 0) {
            return requestSpec.body(requestBody).exchange(
                    (clientRequest, clientResponse) -> readResponse(clientResponse));
        }
        return requestSpec.exchange((clientRequest, clientResponse) -> readResponse(clientResponse));
    }

    private static boolean isTransientStatus(HttpStatusCode status) {
        return switch (status.value()) {
            case 408, 500, 502, 503, 504 -> true;
            default -> false;
        };
    }

    private void recordRetryAttempt(GatewayRoute route, String failureClass) {
        retryCounter(
                        "gateway.upstream.retry.attempts",
                        "Automatic upstream retries attempted by the gateway",
                        route,
                        "failure_class",
                        failureClass)
                .increment();
    }

    private void recordRetrySkipped(GatewayRoute route, GatewayRetryPolicy.RetrySkipReason reason) {
        recordRetrySkipped(route, reason.name().toLowerCase(Locale.ROOT));
    }

    private void recordRetrySkipped(GatewayRoute route, String reason) {
        retryCounter(
                        "gateway.upstream.retry.skipped",
                        "Transient upstream outcomes not retried by the gateway",
                        route,
                        "reason",
                        reason)
                .increment();
    }

    private Counter retryCounter(
            String meterName, String description, GatewayRoute route, String tagName, String tagValue) {
        String routeId = route.id() == null || route.id().isBlank() ? "unknown" : route.id();
        RetryMetricKey key = new RetryMetricKey(meterName, routeId, tagName, tagValue);
        return retryCounters.computeIfAbsent(
                key,
                ignored -> Counter.builder(meterName)
                        .description(description)
                        .tag("route", routeId)
                        .tag(tagName, tagValue)
                        .register(meterRegistry));
    }

    private byte[] readRequestBody(HttpServletRequest request, HttpMethod method) throws IOException {
        long declaredLength = request.getContentLengthLong();
        if (declaredLength == 0 || !mayHaveBody(method)) {
            return new byte[0];
        }
        if (declaredLength > properties.getMaxRequestBodyBytes()) {
            throw new GatewayPayloadTooLargeException();
        }
        return readBounded(request.getInputStream(), properties.getMaxRequestBodyBytes());
    }

    private static boolean mayHaveBody(HttpMethod method) {
        return method == HttpMethod.POST
                || method == HttpMethod.PUT
                || method == HttpMethod.PATCH
                || method == HttpMethod.DELETE;
    }

    /**
     * Rejects request framing on the exact HLS response-only exception before it can hold an
     * unread servlet body while a long response relay occupies bounded stream admission.
     */
    private static void rejectMediaHlsRequestBodyFraming(HttpServletRequest request) {
        if (request.getContentLengthLong() > 0
                || (request.getHeader(HttpHeaders.TRANSFER_ENCODING) != null
                        && !request.getHeader(HttpHeaders.TRANSFER_ENCODING).isBlank())) {
            throw new GatewayBadRequestException();
        }
    }

    private static URI targetUri(GatewayRoute route, HttpServletRequest request) {
        String base = route.upstream().toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String requestUri = pathWithoutContext(request);
        String query = request.getQueryString();
        String target = base + requestUri + (query == null ? "" : "?" + query);
        try {
            return URI.create(target);
        } catch (IllegalArgumentException exception) {
            throw new GatewayBadRequestException(exception);
        }
    }

    private static String pathWithoutContext(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath == null || contextPath.isEmpty()) {
            if (requestUri == null || !requestUri.startsWith("/")) {
                throw new GatewayBadRequestException();
            }
            return requestUri;
        }
        if (requestUri == null || !requestUri.startsWith(contextPath)) {
            throw new GatewayBadRequestException();
        }
        String path = requestUri.substring(contextPath.length());
        if (path.isEmpty() || !path.startsWith("/")) {
            throw new GatewayBadRequestException();
        }
        return path;
    }

    private void copyRequestHeaders(
            HttpServletRequest request,
            HttpHeaders target,
            String correlationId,
            GatewayAuthenticatedSubject subject) {
        Enumeration<String> names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            if (isHopByHop(name)) {
                continue;
            }
            Enumeration<String> values = request.getHeaders(name);
            while (values != null && values.hasMoreElements()) {
                target.add(name, values.nextElement());
            }
        }
        target.set("X-Forwarded-For", request.getRemoteAddr());
        target.set("X-Forwarded-Proto", request.getScheme());
        target.set("X-Forwarded-Host", request.getServerName());
        target.set(CorrelationIdSupport.HEADER_NAME, correlationId);
        if (subject != null) {
            target.set(GatewayAuthenticatedSubject.ACCOUNT_ID_HEADER, subject.accountId().toString());
            target.set(GatewayAuthenticatedSubject.SESSION_ID_HEADER, subject.sessionId().toString());
            target.set(GatewayAuthenticatedSubject.AUTHENTICATION_METHOD_HEADER, subject.authenticationMethod());
            addAnalyticsGatewayProof(request, target, subject);
        }
    }

    private void addAnalyticsGatewayProof(
            HttpServletRequest request, HttpHeaders target, GatewayAuthenticatedSubject subject) {
        String secret = properties.getAnalyticsProofSecret();
        if (secret == null || secret.isBlank()) {
            return;
        }
        String payload = request.getMethod()
                + "\n"
                + pathWithoutContext(request)
                + "\n"
                + subject.accountId()
                + "\n"
                + subject.sessionId();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            target.set("X-LifeOS-Gateway-Proof", HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8))));
        } catch (Exception exception) {
            throw new IllegalStateException("analytics gateway proof could not be generated", exception);
        }
    }

    private DownstreamResponse readResponse(ClientHttpResponse response) throws IOException {
        byte[] body = readBounded(response.getBody(), properties.getMaxResponseBodyBytes());
        return new DownstreamResponse(response.getStatusCode(), response.getHeaders(), body);
    }

    private static void writeResponse(
            HttpServletResponse response, DownstreamResponse downstream, HttpMethod method) throws IOException {
        response.setStatus(downstream.status().value());
        copyResponseHeaders(response, downstream.headers());
        if (method != HttpMethod.HEAD && downstream.body().length > 0) {
            response.setContentLength(downstream.body().length);
            response.getOutputStream().write(downstream.body());
        } else if (method != HttpMethod.HEAD) {
            response.setContentLength(0);
        }
    }

    /**
     * Copies only response headers that are safe at a proxy boundary.
     *
     * <p>Content length is always omitted: buffered responses recalculate it after the copy and
     * SSE responses intentionally use servlet-managed chunking. The correlation filter owns the
     * public correlation ID, so an upstream cannot replace it.
     */
    private static void copyResponseHeaders(HttpServletResponse response, HttpHeaders headers) {
        headers.forEach((name, values) -> {
            if (isHopByHop(name) || "content-length".equalsIgnoreCase(name)) {
                return;
            }
            for (String value : values) {
                response.addHeader(name, value);
            }
        });
    }

    private static byte[] readBounded(InputStream input, long limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(limit, 8192));
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > limit) {
                throw new GatewayPayloadTooLargeException();
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    /**
     * Copies an upload incrementally while enforcing its actual byte count.
     *
     * <p>This is O(n) time for n transferred bytes and O(1) heap space beyond its fixed
     * eight-kibibyte relay buffer. A missing or dishonest content-length cannot bypass the
     * declared request ceiling because the over-limit read is detected before it is written to the
     * upstream socket.
     */
    private static void copyBounded(HttpServletRequest request, OutputStream output, long limit) throws IOException {
        InputStream input;
        try {
            input = request.getInputStream();
        } catch (IOException exception) {
            throw new GatewayClientRequestAbortedException(exception);
        }
        byte[] buffer = new byte[8192];
        long total = 0;
        int bytesRead;
        while (true) {
            try {
                bytesRead = input.read(buffer);
            } catch (IOException exception) {
                throw new GatewayClientRequestAbortedException(exception);
            }
            if (bytesRead == -1) {
                break;
            }
            total += bytesRead;
            if (total > limit) {
                throw new GatewayPayloadTooLargeException();
            }
            output.write(buffer, 0, bytesRead);
        }
        output.flush();
    }

    private static boolean isHopByHop(String name) {
        String normalizedName = name.toLowerCase(Locale.ROOT);
        return normalizedName.startsWith("x-forwarded-") || HOP_BY_HOP_HEADERS.contains(normalizedName);
    }

    private static boolean isTimeout(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof java.net.SocketTimeoutException
                    || current instanceof java.net.http.HttpTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean hasPayloadTooLargeCause(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof GatewayPayloadTooLargeException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean hasClientRequestAbortCause(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof GatewayClientRequestAbortedException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * Separates an inbound client disconnect from a failed Media or Document Vault dependency.
     * The exception is intentionally content-free because it can be carried through RestClient
     * wrapping before the request handler turns it into a generic client error.
     */
    private static final class GatewayClientRequestAbortedException extends IOException {

        private GatewayClientRequestAbortedException(IOException cause) {
            super(null, cause);
        }
    }

    private static void logUpstreamFailure(GatewayRoute route, HttpStatus status, String failureClass) {
        LOGGER.warn(
                "gateway upstream request failed routeId={} status={} failureClass={} correlationId={}",
                route.id(),
                status.value(),
                failureClass,
                RequestContext.CORRELATION_ID.isBound() ? RequestContext.CORRELATION_ID.get() : "unbound");
    }

    private record DownstreamResponse(HttpStatusCode status, HttpHeaders headers, byte[] body) {
    }

    private record RetryMetricKey(String meterName, String routeId, String tagName, String tagValue) {
    }
}
