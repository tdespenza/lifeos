package com.lifeos.gateway.routing;

import com.lifeos.gateway.auth.GatewayAuthenticatedSubject;
import com.lifeos.gateway.config.GatewayProperties;
import com.lifeos.gateway.observability.CorrelationIdSupport;
import com.lifeos.gateway.observability.RequestContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Proxies an allow-listed request while preserving its public HTTP contract.
 *
 * <p>The implementation buffers only bounded request and response bodies. Hop-by-hop headers and
 * caller-supplied routing headers are removed, while the validated correlation ID is installed
 * exactly once on the downstream request.
 *
 * <p>{@code readBounded} consumes at most the configured limit plus one fixed-size read buffer,
 * runs in O(n) time for n bytes consumed, and uses O(limit) space per request. Inbound body buffers
 * are independently bounded by gateway-wide semaphores and aggregate byte budgets. The
 * response-buffer admission is held through the client write, so the worst-case response-buffer
 * footprint is approximately {@code maxResponseBodyBytes * maxConcurrentResponseBuffers} per
 * gateway instance and must fit the configured aggregate response budget, independent of the
 * upstream route bulkhead.
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
            "x-lifeos-workload-identity",
            "x-lifeos-workload-token");

    private final RestClient restClient;
    private final GatewayProperties properties;
    private final GatewayUpstreamResilience resilience;
    private final Semaphore requestBodyAdmission;
    private final Counter requestBodyCapacityRejections;
    private final Semaphore responseBufferAdmission;
    private final Counter responseBufferCapacityRejections;
    private final AtomicInteger inFlightRequests = new AtomicInteger();

    /**
     * Creates a forwarder with the configured outbound HTTP client.
     *
     * @param restClient outbound HTTP client
     * @param properties gateway bounds
     * @param meterRegistry metrics registry for bounded in-flight request instrumentation
     */
    @Autowired
    public GatewayForwarder(
            RestClient restClient,
            GatewayProperties properties,
            MeterRegistry meterRegistry,
            GatewayUpstreamResilience resilience) {
        this.restClient = restClient;
        this.properties = properties;
        this.resilience = resilience;
        this.requestBodyAdmission = new Semaphore(properties.getMaxConcurrentRequestBodyBuffers(), true);
        this.requestBodyCapacityRejections = Counter.builder("gateway.request.body.capacity.rejections")
                .description("Requests rejected because bounded inbound body buffering is full")
                .register(meterRegistry);
        this.responseBufferAdmission = new Semaphore(properties.getMaxConcurrentResponseBuffers(), true);
        this.responseBufferCapacityRejections = Counter.builder("gateway.response.buffer.capacity.rejections")
                .description("Requests rejected because bounded response buffering is full")
                .register(meterRegistry);
        meterRegistry.gauge("gateway.inflight.requests", inFlightRequests);
    }

    /**
     * Creates a forwarder with its own in-process route resilience. Test-only: production wiring
     * must use the injected shared {@link GatewayUpstreamResilience}.
     */
    GatewayForwarder(RestClient restClient, GatewayProperties properties, MeterRegistry meterRegistry) {
        this(restClient, properties, meterRegistry, new GatewayUpstreamResilience(properties, meterRegistry));
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
        HttpMethod method = HttpMethod.valueOf(request.getMethod());
        boolean bodyAdmissionAcquired = acquireRequestBodyAdmission(request, method);
        boolean responseBufferAdmissionAcquired = false;
        try {
            byte[] requestBody = readRequestBody(request, method);
            URI target = targetUri(route, request);

            RestClient.RequestBodySpec requestSpec = restClient.method(method).uri(target);
            requestSpec.headers(headers -> copyRequestHeaders(request, headers, correlationId, subject));

            RestClient.RequestHeadersSpec<?> outgoing = requestSpec;
            if (requestBody.length > 0) {
                outgoing = requestSpec.body(requestBody);
            }

            responseBufferAdmissionAcquired = acquireResponseBufferAdmission();
            GatewayUpstreamResilience.Permit permit = resilience.acquire(route);
            DownstreamResponse downstream = null;
            try (permit) {
                inFlightRequests.incrementAndGet();
                try {
                    downstream = outgoing.exchange(
                            (clientRequest, clientResponse) -> readResponse(clientResponse));
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

    private boolean acquireResponseBufferAdmission() {
        if (!responseBufferAdmission.tryAcquire()) {
            responseBufferCapacityRejections.increment();
            throw new GatewayResponseBufferCapacityException();
        }
        return true;
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

    private static URI targetUri(GatewayRoute route, HttpServletRequest request) {
        String base = route.upstream().toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty()) {
            if (requestUri == null || !requestUri.startsWith(contextPath)) {
                throw new GatewayBadRequestException();
            }
            requestUri = requestUri.substring(contextPath.length());
        }
        String query = request.getQueryString();
        String target = base + requestUri + (query == null ? "" : "?" + query);
        try {
            return URI.create(target);
        } catch (IllegalArgumentException exception) {
            throw new GatewayBadRequestException(exception);
        }
    }

    private static void copyRequestHeaders(
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
        }
    }

    private DownstreamResponse readResponse(ClientHttpResponse response) throws IOException {
        byte[] body = readBounded(response.getBody(), properties.getMaxResponseBodyBytes());
        return new DownstreamResponse(response.getStatusCode(), response.getHeaders(), body);
    }

    private static void writeResponse(
            HttpServletResponse response, DownstreamResponse downstream, HttpMethod method) throws IOException {
        response.setStatus(downstream.status().value());
        downstream.headers().forEach((name, values) -> {
            if (isHopByHop(name) || "content-length".equalsIgnoreCase(name)) {
                return;
            }
            for (String value : values) {
                response.addHeader(name, value);
            }
        });
        if (method != HttpMethod.HEAD && downstream.body().length > 0) {
            response.setContentLength(downstream.body().length);
            response.getOutputStream().write(downstream.body());
        } else if (method != HttpMethod.HEAD) {
            response.setContentLength(0);
        }
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
}
