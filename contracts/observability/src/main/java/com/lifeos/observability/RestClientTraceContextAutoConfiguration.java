package com.lifeos.observability;

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import java.io.IOException;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Applies explicit W3C trace-context propagation to every Spring-managed outbound RestClient.
 *
 * <p>Spring observation creates spans, but propagation must also be explicit for clients that
 * replace the request factory or clone the managed builder. This auto-configuration is supplied
 * by the shared observability contract so every Boot service gets the same boundary behavior.
 */
@AutoConfiguration
public class RestClientTraceContextAutoConfiguration {

    /**
     * Registers the interceptor through Boot's supported builder customization hook.
     *
     * @return a deterministic RestClient customization
     */
    @Bean
    RestClientCustomizer restClientTraceContextCustomizer() {
        return builder -> builder.requestInterceptor(new W3cTraceContextInterceptor());
    }

    private static final class W3cTraceContextInterceptor implements ClientHttpRequestInterceptor {

        @Override
        public ClientHttpResponse intercept(
                HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
            W3CTraceContextPropagator.getInstance().inject(
                    Context.current(), request.getHeaders(), (headers, key, value) -> headers.set(key, value));
            return execution.execute(request, body);
        }
    }
}
