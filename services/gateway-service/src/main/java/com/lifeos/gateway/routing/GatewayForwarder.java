package com.lifeos.gateway.routing;

import com.lifeos.gateway.config.GatewayProperties;
import com.lifeos.gateway.observability.CorrelationIdSupport;
import com.lifeos.gateway.observability.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Set;
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
            "x-forwarded-proto");

    private final RestClient restClient;
    private final GatewayProperties properties;

    /**
     * Creates a forwarder with the configured outbound HTTP client.
     *
     * @param restClient outbound HTTP client
     * @param properties gateway bounds
     */
    public GatewayForwarder(RestClient restClient, GatewayProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
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
        HttpMethod method = HttpMethod.valueOf(request.getMethod());
        byte[] requestBody = readRequestBody(request, method);
        URI target = targetUri(route, request);

        RestClient.RequestBodySpec requestSpec = restClient.method(method).uri(target);
        requestSpec.headers(headers -> copyRequestHeaders(request, headers, correlationId));

        RestClient.RequestHeadersSpec<?> outgoing = requestSpec;
        if (requestBody.length > 0) {
            outgoing = requestSpec.body(requestBody);
        }

        try {
            DownstreamResponse downstream = outgoing.exchange((clientRequest, clientResponse) -> readResponse(clientResponse));
            writeResponse(response, downstream, method);
        } catch (GatewayPayloadTooLargeException exception) {
            throw exception;
        } catch (ResourceAccessException exception) {
            HttpStatus status = isTimeout(exception) ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY;
            logUpstreamFailure(route, status, exception);
            throw new GatewayUpstreamException(status, exception);
        } catch (RestClientException exception) {
            logUpstreamFailure(route, HttpStatus.BAD_GATEWAY, exception);
            throw new GatewayUpstreamException(HttpStatus.BAD_GATEWAY, exception);
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
        String query = request.getQueryString();
        String target = base + request.getRequestURI() + (query == null ? "" : "?" + query);
        return URI.create(target);
    }

    private static void copyRequestHeaders(
            HttpServletRequest request, HttpHeaders target, String correlationId) {
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
        target.set(CorrelationIdSupport.HEADER_NAME, correlationId);
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
        return HOP_BY_HOP_HEADERS.contains(name.toLowerCase(Locale.ROOT));
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

    private static void logUpstreamFailure(GatewayRoute route, HttpStatus status, Throwable exception) {
        LOGGER.warn(
                "gateway upstream request failed routeId={} status={} correlationIdBound={}",
                route.id(),
                status.value(),
                RequestContext.CORRELATION_ID.isBound());
    }

    private record DownstreamResponse(HttpStatusCode status, HttpHeaders headers, byte[] body) {
    }
}
