package com.lifeos.identity.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates and bounds authorization-decision requests before JSON binding.
 *
 * <p>Spring MVC deserializes an {@code @RequestBody} before controller code executes. This filter
 * authenticates the workload and applies its distributed budget first, then reads at most a small,
 * fixed request body into a replayable wrapper. It therefore prevents an unauthenticated request
 * from allocating an unbounded attributes map or reaching durable audit persistence.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InternalAuthorizationRequestFilter extends OncePerRequestFilter {

    /** Internal decision path protected before any request-body binding. */
    public static final String DECISION_PATH = "/api/v1/internal/authorization/decisions";

    /** Request attribute holding a workload identity proven by this filter. */
    public static final String VERIFIED_WORKLOAD_IDENTITY_ATTRIBUTE =
            InternalAuthorizationRequestFilter.class.getName() + ".verifiedWorkloadIdentity";

    /** Fixed maximum JSON body size for the intentionally small decision contract. */
    static final int MAX_DECISION_REQUEST_BYTES = 16 * 1024;

    private final InternalWorkloadIdentityVerifier workloadIdentityVerifier;
    private final InternalWorkloadRateLimiter workloadRateLimiter;

    /**
     * Creates the pre-binding authorization guard.
     *
     * @param workloadIdentityVerifier workload credential verifier
     * @param workloadRateLimiter distributed per-workload request limiter
     */
    public InternalAuthorizationRequestFilter(
            InternalWorkloadIdentityVerifier workloadIdentityVerifier,
            InternalWorkloadRateLimiter workloadRateLimiter) {
        this.workloadIdentityVerifier = workloadIdentityVerifier;
        this.workloadRateLimiter = workloadRateLimiter;
    }

    /**
     * Returns the workload identity verified before binding, if this request passed the filter.
     *
     * @param request current request
     * @return verified identity, or {@code null} for direct controller/unit-test invocation
     */
    public static String verifiedWorkloadIdentity(HttpServletRequest request) {
        Object value = request.getAttribute(VERIFIED_WORKLOAD_IDENTITY_ATTRIBUTE);
        return value instanceof String identity ? identity : null;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String contextPath = request.getContextPath();
        String requestPath = request.getRequestURI();
        String path = contextPath == null || contextPath.isEmpty()
                ? requestPath
                : requestPath.substring(contextPath.length());
        return !(DECISION_PATH.equals(path) || path.startsWith(DECISION_PATH + ";"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_DECISION_REQUEST_BYTES) {
            writeProblem(response, HttpStatus.PAYLOAD_TOO_LARGE, "Internal authorization request is too large.");
            return;
        }

        String workloadIdentity;
        try {
            workloadIdentity = workloadIdentityVerifier.verify(request);
            workloadRateLimiter.check(workloadIdentity);
        } catch (InternalWorkloadAuthenticationException exception) {
            // Do not synchronously persist an event for an unauthenticated caller. That write path
            // would let an attacker exhaust the audit database before proving a workload identity.
            writeProblem(response, HttpStatus.UNAUTHORIZED, "Internal authorization request failed.");
            return;
        } catch (InternalWorkloadRateLimitExceededException exception) {
            response.setHeader("Retry-After", Long.toString(exception.getRetryAfterSeconds()));
            writeProblem(response, HttpStatus.TOO_MANY_REQUESTS,
                    "Internal authorization requests are temporarily limited.");
            return;
        } catch (AuthenticationDependencyUnavailableException exception) {
            writeProblem(response, HttpStatus.SERVICE_UNAVAILABLE, "Authorization is temporarily unavailable.");
            return;
        }

        byte[] body;
        try {
            body = request.getInputStream().readNBytes(MAX_DECISION_REQUEST_BYTES + 1);
        } catch (IOException exception) {
            writeProblem(response, HttpStatus.BAD_REQUEST, "Internal authorization request failed.");
            return;
        }
        if (body.length > MAX_DECISION_REQUEST_BYTES) {
            writeProblem(response, HttpStatus.PAYLOAD_TOO_LARGE, "Internal authorization request is too large.");
            return;
        }

        CachedBodyRequest wrapped = new CachedBodyRequest(request, body);
        wrapped.setAttribute(VERIFIED_WORKLOAD_IDENTITY_ATTRIBUTE, workloadIdentity);
        filterChain.doFilter(wrapped, response);
    }

    private void writeProblem(HttpServletResponse response, HttpStatus status, String detail) throws IOException {
        response.setStatus(status.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/problem+json");
        response.getWriter().write("""
                {"type":"about:blank","title":"Internal authorization request failed","status":%d,"detail":"%s"}
                """.formatted(status.value(), detail));
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new CachedServletInputStream(body);
        }

        @Override
        public BufferedReader getReader() {
            Charset charset;
            try {
                charset = getCharacterEncoding() == null
                        ? StandardCharsets.UTF_8
                        : Charset.forName(getCharacterEncoding());
            } catch (RuntimeException exception) {
                charset = StandardCharsets.UTF_8;
            }
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }

    private static final class CachedServletInputStream extends ServletInputStream {

        private final ByteArrayInputStream delegate;

        private CachedServletInputStream(byte[] body) {
            this.delegate = new ByteArrayInputStream(body);
        }

        @Override
        public int read() {
            return delegate.read();
        }

        @Override
        public boolean isFinished() {
            return delegate.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            throw new UnsupportedOperationException("Asynchronous reads are not supported for cached request bodies");
        }
    }
}
