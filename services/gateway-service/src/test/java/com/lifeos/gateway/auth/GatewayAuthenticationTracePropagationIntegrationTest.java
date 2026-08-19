package com.lifeos.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Verifies that the observation-enabled gateway identity hop carries W3C trace context. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "gateway.authentication.workload-token=test-only-gateway-workload-token",
            "gateway.rate-limit.key-secret=test-only-rate-limit-secret"
        })
class GatewayAuthenticationTracePropagationIntegrationTest {

    private static final String ACCESS_TOKEN_PROOF =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final AtomicReference<String> traceparent = new AtomicReference<>();
    private static HttpServer identityServer;

    @Autowired
    private GatewayAuthenticationClient client;

    @Autowired
    private Tracer tracer;

    @BeforeAll
    static void startIdentityServer() throws IOException {
        startServerIfNecessary();
    }

    private static void startServerIfNecessary() throws IOException {
        if (identityServer != null) {
            return;
        }
        identityServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        identityServer.createContext("/api/v1/auth/validate", GatewayAuthenticationTracePropagationIntegrationTest::respond);
        identityServer.start();
    }

    @AfterAll
    static void stopIdentityServer() {
        if (identityServer != null) {
            identityServer.stop(0);
        }
    }

    @DynamicPropertySource
    static void identityProperties(DynamicPropertyRegistry registry) {
        if (identityServer == null) {
            try {
                startServerIfNecessary();
            } catch (IOException exception) {
                throw new IllegalStateException("identity test server could not start", exception);
            }
        }
        registry.add(
                "gateway.authentication.base-url",
                () -> "http://127.0.0.1:" + identityServer.getAddress().getPort());
    }

    @Test
    void propagatesW3cTraceparentToIdentityValidation() {
        Span span = tracer.nextSpan().name("gateway-authentication-test").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            client.authenticate("Bearer signed-access-token");
        } finally {
            span.end();
        }

        assertThat(traceparent.get())
                .as("identity validation must receive the active W3C trace context")
                .matches("^00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]$");
    }

    private static void respond(HttpExchange exchange) throws IOException {
        traceparent.set(exchange.getRequestHeaders().getFirst("traceparent"));
        UUID accountId = UUID.fromString("00000000-0000-4000-8000-000000000001");
        UUID sessionId = UUID.fromString("00000000-0000-4000-8000-000000000002");
        byte[] body = ("{\"accountId\":\"%s\",\"sessionId\":\"%s\",\"authenticationMethod\":\"PASSWORD\","
                        + "\"accessTokenProof\":\"%s\"}")
                .formatted(accountId, sessionId, ACCESS_TOKEN_PROOF)
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}
