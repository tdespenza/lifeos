package com.lifeos.gateway.observability;

import java.io.IOException;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Injects the active W3C trace context into each gateway outbound HTTP request.
 *
 * <p>Spring observation instrumentation creates the outbound span, while this small boundary
 * interceptor makes the propagation contract explicit for every client factory, including the
 * separately bounded streaming and upload clients. The standard propagator writes {@code
 * traceparent} and {@code tracestate} when the current context contains a sampled or unsampled
 * span; it is a no-op when tracing is disabled. Existing caller-supplied values are replaced by
 * the active span so a downstream service cannot be attached to a stale or unrelated trace.
 */
public final class W3cTraceContextClientInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(
            HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        W3CTraceContextPropagator.getInstance().inject(
                Context.current(), request.getHeaders(), (headers, key, value) -> headers.set(key, value));
        return execution.execute(request, body);
    }
}
