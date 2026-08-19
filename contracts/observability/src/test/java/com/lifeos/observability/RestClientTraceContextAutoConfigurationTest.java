package com.lifeos.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.hamcrest.Matchers.matchesPattern;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RestClientTraceContextAutoConfigurationTest {

    @Test
    void autoConfigurationPublishesTheRestClientCustomizer() {
        new ApplicationContextRunner()
                .withUserConfiguration(RestClientTraceContextAutoConfiguration.class)
                .run(context -> assertThat(context).hasSingleBean(
                        org.springframework.boot.web.client.RestClientCustomizer.class));
    }

    @Test
    void interceptorWritesTraceparentForActiveSpan() {
        try (SdkTracerProvider provider = SdkTracerProvider.builder().build()) {
            Span span = provider.get("observability-test").spanBuilder("outbound").startSpan();
            try (Scope ignored = span.makeCurrent()) {
                RestClient.Builder builder = RestClient.builder();
                new RestClientTraceContextAutoConfiguration()
                        .restClientTraceContextCustomizer()
                        .customize(builder);
                MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
                server.expect(requestTo("https://identity.test/validate"))
                        .andExpect(header("traceparent", matchesPattern("00-[0-9a-f]{32}-[0-9a-f]{16}-01")))
                        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
                builder.build().get().uri("https://identity.test/validate").retrieve().toBodilessEntity();
                server.verify();
            } finally {
                span.end();
            }
        }
    }
}
