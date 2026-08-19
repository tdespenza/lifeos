package com.lifeos.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lifeos.gateway.auth.GatewayAuthenticationClient;
import com.lifeos.gateway.auth.GatewayAuthenticatedSubject;
import com.lifeos.gateway.auth.GatewayAuthenticationMetrics;
import com.lifeos.gateway.auth.GatewayAuthenticationService;
import com.lifeos.gateway.config.GatewayAuthenticationProperties;
import com.lifeos.gateway.config.GatewayProperties;
import com.lifeos.gateway.observability.CorrelationIdFilter;
import com.lifeos.gateway.observability.CorrelationIdSupport;
import com.lifeos.gateway.ratelimit.GatewayRateLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

class GatewayControllerTest {

    private static final String UPSTREAM = "https://task-goal.test";
    private static final String NOTIFICATION_UPSTREAM = "https://notification.test";
    private static final String MEDIA_UPSTREAM = "https://media.test";
    private static final String AI_ASSISTANT_UPSTREAM = "https://assistant.test";
    private static final String CORRELATION_ID = "11111111-1111-4111-8111-111111111111";
    private static final String UUID_V7_CORRELATION_ID = "11111111-1111-7111-8111-111111111111";

    private MockRestServiceServer upstream;
    private MockRestServiceServer identity;
    private MockMvc mockMvc;
    private GatewayProperties properties;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        properties = new GatewayProperties();
        // These fixtures exercise Story 2.1 forwarding behavior. Protected-route behavior is
        // covered below with an explicit authenticated route fixture.
        properties.setRoutes(List.of(
                new GatewayProperties.Route("goals", "/api/v1/goals", UPSTREAM, false)));
        properties.getUpstream().getRetry().setInitialBackoff(Duration.ofMillis(1));
        properties.getUpstream().getRetry().setMaxBackoff(Duration.ofMillis(1));
        RestClient.Builder builder = RestClient.builder();
        upstream = MockRestServiceServer.bindTo(builder).build();
        meterRegistry = new SimpleMeterRegistry();
        GatewayForwarder forwarder = new GatewayForwarder(builder.build(), properties, meterRegistry);
        GatewayAuthenticationProperties authenticationProperties = configuredAuthenticationProperties();
        GatewayAuthenticationService authenticationService = new GatewayAuthenticationService(
                new GatewayAuthenticationClient(
                        RestClient.builder().baseUrl(authenticationProperties.getBaseUrl()).build(),
                        authenticationProperties),
                new GatewayAuthenticationMetrics(meterRegistry),
                authenticationProperties);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new GatewayController(
                                new GatewayRouteTable(properties),
                                forwarder,
                                authenticationService,
                                (ignoredRoute, ignoredRequest, ignoredSubject) -> {}))
                .addFilters(new CorrelationIdFilter())
                .build();
    }

    @Test
    void forwardsConfiguredRouteBodyQueryHeadersAndOneCorrelationId() throws Exception {
        HttpHeaders upstreamHeaders = new HttpHeaders();
        upstreamHeaders.set("X-Upstream", "task-goal");
        upstreamHeaders.set("X-Correlation-ID", "22222222-2222-4222-8222-222222222222");
        upstream.expect(requestTo(UPSTREAM + "/api/v1/goals?view=full"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
                .andExpect(header("X-Correlation-ID", CORRELATION_ID))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers
                        .content().json("{\"title\":\"Ship gateway\"}"))
                .andRespond(withSuccess("{\"id\":\"goal-1\"}", MediaType.APPLICATION_JSON)
                        .headers(upstreamHeaders));

        mockMvc.perform(post("/api/v1/goals?view=full")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                        .header("X-Correlation-ID", CORRELATION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Ship gateway\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-ID", CORRELATION_ID))
                .andExpect(header().string("X-Upstream", "task-goal"))
                .andExpect(content().json("{\"id\":\"goal-1\"}"));

        upstream.verify();
    }

    @Test
    void rejectsMissingBearerWithoutForwardingAndRecordsOnlyRedactedSecurityMetrics() throws Exception {
        useProtectedRoute();

        mockMvc.perform(get("/api/v1/goals"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        assertThat(meterRegistry.get("gateway.authentication.rejections")
                .tag("route", "goals")
                .tag("reason", "invalid_credentials")
                .counter()
                .count()).isEqualTo(1);
        upstream.verify();
        identity.verify();
    }

    @Test
    void validatesBearerWithIdentityBeforeForwardingSanitizedSubjectContext() throws Exception {
        useProtectedRoute();
        UUID accountId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        identity.expect(requestTo("https://identity.test/api/v1/auth/validate"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer signed-access-token"))
                .andExpect(header("X-LifeOS-Workload-Identity", "gateway-service"))
                .andExpect(header("X-LifeOS-Workload-Token", "test-gateway-workload-token"))
                .andRespond(withSuccess("""
                        {"accountId":"%s","sessionId":"%s","authenticationMethod":"PASSWORD",
                         "accessTokenProof":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
                        """.formatted(accountId, sessionId), MediaType.APPLICATION_JSON));
        upstream.expect(requestTo(UPSTREAM + "/api/v1/goals"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer signed-access-token"))
                .andExpect(header("X-LifeOS-Authenticated-Account-Id", accountId.toString()))
                .andExpect(header("X-LifeOS-Authenticated-Session-Id", sessionId.toString()))
                .andExpect(header("X-LifeOS-Authentication-Method", "PASSWORD"))
                .andExpect(headerDoesNotExist("X-LifeOS-Workload-Identity"))
                .andExpect(headerDoesNotExist("X-LifeOS-Workload-Token"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        mockMvc.perform(get("/api/v1/goals")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer signed-access-token")
                        .header("X-LifeOS-Authenticated-Account-Id", "attacker-account")
                        .header("X-LifeOS-Authenticated-Session-Id", "attacker-session")
                        .header("X-LifeOS-Authentication-Method", "attacker-method")
                        .header("X-LifeOS-Workload-Token", "attacker-token"))
                .andExpect(status().isOk());

        identity.verify();
        upstream.verify();
    }

    @Test
    void mapsExpiredOrRevokedIdentityRejectionsToUnauthorizedWithoutForwarding() throws Exception {
        useProtectedRoute();
        identity.expect(requestTo("https://identity.test/api/v1/auth/validate"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withUnauthorizedRequest());

        mockMvc.perform(get("/api/v1/goals")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer expired-or-revoked"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        identity.verify();
        upstream.verify();
    }

    @Test
    void failsClosedWhenIdentityValidationIsUnavailable() throws Exception {
        useProtectedRoute();
        identity.expect(requestTo("https://identity.test/api/v1/auth/validate"))
                .andRespond(withServerError());

        mockMvc.perform(get("/api/v1/goals")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer valid-format-token"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, matchesPattern("(?:[5-9]|1[0-5])")))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_UNAVAILABLE"));

        assertThat(meterRegistry.get("gateway.authentication.rejections")
                .tag("route", "goals")
                .tag("reason", "identity_unavailable")
                .counter()
                .count()).isEqualTo(1);
        identity.verify();
        upstream.verify();
    }

    @Test
    void honorsMethodScopedAuthenticationPolicies() throws Exception {
        useProtectedRoute(Set.of("POST"));

        mockMvc.perform(post("/api/v1/goals"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        upstream.expect(requestTo(UPSTREAM + "/api/v1/goals"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(headerDoesNotExist("X-LifeOS-Authenticated-Account-Id"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        mockMvc.perform(get("/api/v1/goals"))
                .andExpect(status().isOk());

        identity.verify();
        upstream.verify();
    }

    @Test
    void generatesAValidatedIdForUnsafeInputAndReturnsItOnTheResponse() throws Exception {
        upstream.expect(requestTo(UPSTREAM + "/api/v1/goals"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Correlation-ID", matchesPattern("[0-9a-f-]{36}")))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        mockMvc.perform(get("/api/v1/goals").header("X-Correlation-ID", "contains spaces"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-ID", matchesPattern("[0-9a-f-]{36}")));

        upstream.verify();
    }

    @Test
    void preservesUuidV7CorrelationIds() throws Exception {
        upstream.expect(requestTo(UPSTREAM + "/api/v1/goals"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Correlation-ID", UUID_V7_CORRELATION_ID))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        mockMvc.perform(get("/api/v1/goals").header("X-Correlation-ID", UUID_V7_CORRELATION_ID))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-ID", UUID_V7_CORRELATION_ID));

        upstream.verify();
    }

    @Test
    void rejectsOversizedRequestBodiesBeforeForwarding() throws Exception {
        properties.setMaxRequestBodyBytes(4);

        mockMvc.perform(post("/api/v1/goals")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("12345"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"))
                .andExpect(jsonPath("$.detail").value("The request exceeds the configured size limit."));
    }

    @Test
    void relaysTheExactDocumentCreateMultipartBodyWithoutUsingTheOrdinaryOneMebibyteBuffer()
            throws Exception {
        GatewayProperties uploadProperties = new GatewayProperties();
        GatewayProperties.Route documents = documentUploadRoute();
        uploadProperties.setRoutes(List.of(documents));
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GatewayForwarder forwarder = new GatewayForwarder(builder.build(), uploadProperties, registry);
        GatewayRoute route = new GatewayRouteTable(uploadProperties)
                .resolve(GatewayRoute.DOCUMENT_UPLOAD_PATH)
                .orElseThrow();
        byte[] multipartBody = new byte[1_048_577];
        multipartBody[0] = '-';
        multipartBody[multipartBody.length - 1] = '-';
        String contentType = "multipart/form-data; boundary=lifeos-upload-boundary";
        HttpServletRequest request = requestWithBody("POST", GatewayRoute.DOCUMENT_UPLOAD_PATH, multipartBody);
        when(request.getHeaderNames()).thenReturn(Collections.enumeration(List.of(
                HttpHeaders.CONTENT_TYPE, HttpHeaders.AUTHORIZATION, "Idempotency-Key", HttpHeaders.IF_MATCH)));
        when(request.getHeaders(HttpHeaders.CONTENT_TYPE)).thenReturn(Collections.enumeration(List.of(contentType)));
        when(request.getHeaders(HttpHeaders.AUTHORIZATION))
                .thenReturn(Collections.enumeration(List.of("Bearer media-access-token")));
        when(request.getHeaders("Idempotency-Key"))
                .thenReturn(Collections.enumeration(List.of("media-upload-idempotency-key")));
        when(request.getHeaders(HttpHeaders.IF_MATCH)).thenReturn(Collections.enumeration(List.of("\"0\"")));

        server.expect(requestTo("https://documents.test" + GatewayRoute.DOCUMENT_UPLOAD_PATH))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.CONTENT_TYPE, contentType))
                .andExpect(header(HttpHeaders.CONTENT_LENGTH, Integer.toString(multipartBody.length)))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers
                        .content().bytes(multipartBody))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\":\"document-1\"}"));

        MockHttpServletResponse response = new MockHttpServletResponse();
        forwarder.forward(request, response, route, CORRELATION_ID);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.CREATED.value());
        assertThat(response.getContentAsString()).isEqualTo("{\"id\":\"document-1\"}");
        assertThat(registry.get("gateway.document.upload.available.permits").gauge().value())
                .isEqualTo(8.0);
        assertThat(registry.get("gateway.document.upload.inflight").gauge().value()).isZero();
        server.verify();
    }

    @Test
    void rejectsAnOversizedDeclaredDocumentCreateBeforeOpeningTheUpstreamConnection() throws Exception {
        GatewayProperties uploadProperties = new GatewayProperties();
        GatewayProperties.Route documents = documentUploadRoute();
        uploadProperties.setRoutes(List.of(documents));
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GatewayForwarder forwarder = new GatewayForwarder(builder.build(), uploadProperties, registry);
        GatewayRoute route = new GatewayRouteTable(uploadProperties)
                .resolve(GatewayRoute.DOCUMENT_UPLOAD_PATH)
                .orElseThrow();
        HttpServletRequest request = requestWithBody(
                "POST", GatewayRoute.DOCUMENT_UPLOAD_PATH, new byte[] {'x'});
        when(request.getContentLengthLong()).thenReturn(
                GatewayProperties.DocumentUpload.MAX_DOCUMENT_UPLOAD_BODY_BYTES + 1);

        assertThatThrownBy(() -> forwarder.forward(
                        request, new MockHttpServletResponse(), route, CORRELATION_ID))
                .isInstanceOf(GatewayPayloadTooLargeException.class);
        assertThat(registry.get("gateway.document.upload.available.permits").gauge().value())
                .isEqualTo(8.0);
        server.verify();
    }

    @Test
    void rejectsAnOversizedChunkedDocumentCreateWhileRelayingWithoutCountingItAsAnUpstreamFailure()
            throws Exception {
        GatewayProperties uploadProperties = new GatewayProperties();
        uploadProperties.getDocumentUpload().setMaxRequestBodyBytes(4);
        GatewayProperties.Route documents = documentUploadRoute();
        uploadProperties.setRoutes(List.of(documents));
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GatewayForwarder forwarder = new GatewayForwarder(builder.build(), uploadProperties, registry);
        GatewayRoute route = new GatewayRouteTable(uploadProperties)
                .resolve(GatewayRoute.DOCUMENT_UPLOAD_PATH)
                .orElseThrow();
        HttpServletRequest request = requestWithBody(
                "POST", GatewayRoute.DOCUMENT_UPLOAD_PATH, "12345".getBytes(StandardCharsets.UTF_8));
        when(request.getContentLengthLong()).thenReturn(-1L);

        assertThatThrownBy(() -> forwarder.forward(
                        request, new MockHttpServletResponse(), route, CORRELATION_ID))
                .isInstanceOf(GatewayPayloadTooLargeException.class);
        assertThat(registry.get("gateway.upstream.failures")
                .tag("route", "document-vault")
                .counter()
                .count()).isZero();
        assertThat(registry.get("gateway.document.upload.available.permits").gauge().value())
                .isEqualTo(8.0);
        server.verify();
    }

    @Test
    void keepsNonCreateDocumentOperationsOnTheOrdinaryBoundedRequestPath() throws Exception {
        GatewayProperties uploadProperties = new GatewayProperties();
        uploadProperties.setMaxRequestBodyBytes(4);
        GatewayProperties.Route documents = documentUploadRoute();
        uploadProperties.setRoutes(List.of(documents));
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GatewayForwarder forwarder = new GatewayForwarder(
                builder.build(), uploadProperties, new SimpleMeterRegistry());
        GatewayRoute route = new GatewayRouteTable(uploadProperties)
                .resolve(GatewayRoute.DOCUMENT_UPLOAD_PATH + "/document-1")
                .orElseThrow();
        HttpServletRequest request = requestWithBody(
                "PUT", GatewayRoute.DOCUMENT_UPLOAD_PATH + "/document-1", "12345".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> forwarder.forward(
                        request, new MockHttpServletResponse(), route, CORRELATION_ID))
                .isInstanceOf(GatewayPayloadTooLargeException.class);
        server.verify();
    }

    @Test
    void rejectsASecondDocumentUploadWhenTheRequestStreamingAdmissionIsFull() throws Exception {
        GatewayProperties uploadProperties = new GatewayProperties();
        uploadProperties.getDocumentUpload().setMaxConcurrentUploads(1);
        GatewayProperties.Route documents = documentUploadRoute();
        uploadProperties.setRoutes(List.of(documents));
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GatewayForwarder forwarder = new GatewayForwarder(builder.build(), uploadProperties, registry);
        GatewayRoute route = new GatewayRouteTable(uploadProperties)
                .resolve(GatewayRoute.DOCUMENT_UPLOAD_PATH)
                .orElseThrow();
        CountDownLatch bodyReadStarted = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        HttpServletRequest stalledRequest = requestWithoutBody("POST", GatewayRoute.DOCUMENT_UPLOAD_PATH);
        when(stalledRequest.getContentLengthLong()).thenReturn(-1L);
        when(stalledRequest.getInputStream()).thenReturn(stalledInputStream(bodyReadStarted, releaseBody));
        HttpServletRequest rejectedRequest = requestWithoutBody("POST", GatewayRoute.DOCUMENT_UPLOAD_PATH);
        when(rejectedRequest.getContentLengthLong()).thenReturn(-1L);
        server.expect(requestTo("https://documents.test" + GatewayRoute.DOCUMENT_UPLOAD_PATH))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> stalled = executor.submit(() -> {
                forwarder.forward(stalledRequest, new MockHttpServletResponse(), route, CORRELATION_ID);
                return null;
            });
            assertThat(bodyReadStarted.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> forwarder.forward(
                            rejectedRequest, new MockHttpServletResponse(), route, CORRELATION_ID))
                    .isInstanceOf(GatewayDocumentUploadCapacityException.class);
            assertThat(registry.get("gateway.document.upload.capacity.rejections").counter().count())
                    .isEqualTo(1.0);
            assertThat(registry.get("gateway.document.upload.available.permits").gauge().value())
                    .isZero();

            releaseBody.countDown();
            assertThat(stalled.get(5, TimeUnit.SECONDS)).isNull();
            server.verify();
        } finally {
            releaseBody.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void neverRetriesTheDocumentCreateRequestAfterATransientUpstreamResponse() throws Exception {
        GatewayProperties uploadProperties = new GatewayProperties();
        GatewayProperties.Route documents = documentUploadRoute();
        uploadProperties.setRoutes(List.of(documents));
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GatewayForwarder forwarder = new GatewayForwarder(builder.build(), uploadProperties, registry);
        GatewayRoute route = new GatewayRouteTable(uploadProperties)
                .resolve(GatewayRoute.DOCUMENT_UPLOAD_PATH)
                .orElseThrow();
        HttpServletRequest request = requestWithBody(
                "POST", GatewayRoute.DOCUMENT_UPLOAD_PATH, new byte[] {'x'});

        server.expect(requestTo("https://documents.test" + GatewayRoute.DOCUMENT_UPLOAD_PATH))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        MockHttpServletResponse response = new MockHttpServletResponse();
        forwarder.forward(request, response, route, CORRELATION_ID);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        assertThat(registry.find("gateway.upstream.retry.attempts").counters()).isEmpty();
        server.verify();
    }

    @Test
    void mapsOpenCircuitsToAControlledRetryableServiceUnavailableResponse() throws Exception {
        mockMvcForUpstreamFailure("circuit_open", 10)
                .perform(get("/api/v1/goals"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "10"))
                .andExpect(jsonPath("$.code").value("UPSTREAM_UNAVAILABLE"))
                .andExpect(jsonPath("$.title").value("Upstream unavailable"))
                .andExpect(jsonPath("$.detail").value("The requested service is temporarily unavailable."));
    }

    @Test
    void mapsRejectedBulkheadsToAControlledRetryableServiceUnavailableResponse() throws Exception {
        mockMvcForUpstreamFailure("bulkhead_rejected", 1)
                .perform(get("/api/v1/goals"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                .andExpect(jsonPath("$.code").value("UPSTREAM_UNAVAILABLE"))
                .andExpect(jsonPath("$.title").value("Upstream unavailable"))
                .andExpect(jsonPath("$.detail").value("The requested service is temporarily unavailable."));
    }

    @Test
    void doesNotConsumeUpstreamBulkheadWhileInboundUploadIsStalled() throws Exception {
        ForwarderFixture fixture = forwarderFixture(properties ->
                properties.getUpstream().getBulkhead().setMaxConcurrentRequests(1));
        GatewayRoute route = new GatewayRoute(
                "goals", "/api/v1/goals", URI.create(UPSTREAM), false, Set.of());
        MockRestServiceServer server = fixture.server();
        server.expect(requestTo(UPSTREAM + "/api/v1/goals"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(requestTo(UPSTREAM + "/api/v1/goals"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        GatewayForwarder forwarder = fixture.forwarder();
        CountDownLatch bodyReadStarted = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        HttpServletRequest stalledRequest = requestWithoutBody("POST");
        when(stalledRequest.getContentLengthLong()).thenReturn(-1L);
        when(stalledRequest.getInputStream()).thenReturn(stalledInputStream(bodyReadStarted, releaseBody));
        HttpServletRequest healthyRequest = requestWithoutBody("GET");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> stalled = executor.submit(() -> {
                forwarder.forward(
                        stalledRequest, new MockHttpServletResponse(), route, CORRELATION_ID);
                return null;
            });
            assertThat(bodyReadStarted.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> healthy = executor.submit(() -> {
                forwarder.forward(
                        healthyRequest, new MockHttpServletResponse(), route, CORRELATION_ID);
                return null;
            });
            assertThat(healthy.get(5, TimeUnit.SECONDS)).isNull();
            assertThat(stalled.isDone()).isFalse();

            releaseBody.countDown();
            assertThat(stalled.get(5, TimeUnit.SECONDS)).isNull();
            server.verify();
        } finally {
            releaseBody.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsASecondInboundBodyWhenRequestBufferCapacityIsFull() throws Exception {
        ForwarderFixture fixture = forwarderFixture(properties ->
                properties.setMaxConcurrentRequestBodyBuffers(1));
        GatewayRoute route = new GatewayRoute(
                "goals", "/api/v1/goals", URI.create(UPSTREAM), false, Set.of());
        MockRestServiceServer server = fixture.server();
        server.expect(requestTo(UPSTREAM + "/api/v1/goals"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        SimpleMeterRegistry registry = fixture.registry();
        GatewayForwarder forwarder = fixture.forwarder();
        CountDownLatch bodyReadStarted = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        HttpServletRequest stalledRequest = requestWithoutBody("POST");
        when(stalledRequest.getContentLengthLong()).thenReturn(-1L);
        when(stalledRequest.getInputStream()).thenReturn(stalledInputStream(bodyReadStarted, releaseBody));
        HttpServletRequest rejectedRequest = requestWithoutBody("POST");
        when(rejectedRequest.getContentLengthLong()).thenReturn(-1L);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> stalled = executor.submit(() -> {
                forwarder.forward(
                        stalledRequest, new MockHttpServletResponse(), route, CORRELATION_ID);
                return null;
            });
            assertThat(bodyReadStarted.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> forwarder.forward(
                            rejectedRequest, new MockHttpServletResponse(), route, CORRELATION_ID))
                    .isInstanceOf(GatewayRequestBodyCapacityException.class);
            assertThat(registry.get("gateway.request.body.capacity.rejections")
                    .counter()
                    .count())
                    .isEqualTo(1.0);
            assertThat(registry.get("gateway.request.body.available.permits")
                    .gauge()
                    .value())
                    .isEqualTo(0.0);

            releaseBody.countDown();
            assertThat(stalled.get(5, TimeUnit.SECONDS)).isNull();
            server.verify();
        } finally {
            releaseBody.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void releasesInboundBodyAdmissionAfterRejectingAnOversizedRequestBody() throws Exception {
        ForwarderFixture fixture = forwarderFixture(properties -> {
            properties.setMaxConcurrentRequestBodyBuffers(1);
            properties.setMaxRequestBodyBytes(4);
        });
        GatewayRoute route = new GatewayRoute(
                "goals", "/api/v1/goals", URI.create(UPSTREAM), false, Set.of());
        HttpServletRequest oversizedRequest = requestWithBody("POST", "12345".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> fixture.forwarder().forward(
                        oversizedRequest, new MockHttpServletResponse(), route, CORRELATION_ID))
                .isInstanceOf(GatewayPayloadTooLargeException.class);
        assertThat(fixture.registry().get("gateway.request.body.available.permits")
                .gauge()
                .value()).isEqualTo(1.0);
        fixture.server().verify();
    }

    @Test
    void retainsInboundBodyAdmissionUntilUpstreamForwardingCompletes() throws Exception {
        ForwarderFixture fixture = forwarderFixture(properties ->
                properties.setMaxConcurrentRequestBodyBuffers(1));
        GatewayRoute route = new GatewayRoute(
                "goals", "/api/v1/goals", URI.create(UPSTREAM), false, Set.of());
        MockRestServiceServer server = fixture.server();
        CountDownLatch upstreamStarted = new CountDownLatch(1);
        CountDownLatch releaseUpstream = new CountDownLatch(1);
        server.expect(requestTo(UPSTREAM + "/api/v1/goals"))
                .andRespond(request -> {
                    upstreamStarted.countDown();
                    awaitLatch(releaseUpstream, "upstream release");
                    return withSuccess("[]", MediaType.APPLICATION_JSON).createResponse(request);
                });

        GatewayForwarder forwarder = fixture.forwarder();
        HttpServletRequest firstRequest = requestWithBody("POST", "first".getBytes(StandardCharsets.UTF_8));
        HttpServletRequest secondRequest = requestWithBody("POST", "second".getBytes(StandardCharsets.UTF_8));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> first = executor.submit(() -> {
                forwarder.forward(firstRequest, new MockHttpServletResponse(), route, CORRELATION_ID);
                return null;
            });
            assertThat(upstreamStarted.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> forwarder.forward(
                            secondRequest, new MockHttpServletResponse(), route, CORRELATION_ID))
                    .isInstanceOf(GatewayRequestBodyCapacityException.class);

            releaseUpstream.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isNull();
            server.verify();
        } finally {
            releaseUpstream.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void releasesUpstreamPermitBeforeWritingClientResponse() throws Exception {
        ForwarderFixture fixture = forwarderFixture(properties -> {
            properties.getUpstream().getBulkhead().setMaxConcurrentRequests(1);
            properties.setMaxConcurrentResponseBuffers(2);
        });
        GatewayRoute route = new GatewayRoute(
                "goals", "/api/v1/goals", URI.create(UPSTREAM), false, Set.of());
        MockRestServiceServer server = fixture.server();
        server.expect(requestTo(UPSTREAM + "/api/v1/goals"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(requestTo(UPSTREAM + "/api/v1/goals"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        GatewayForwarder forwarder = fixture.forwarder();
        CountDownLatch responseWriteStarted = new CountDownLatch(1);
        CountDownLatch releaseResponseWrite = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> {
                forwarder.forward(
                        requestWithoutBody("GET"),
                        blockingResponse(responseWriteStarted, releaseResponseWrite),
                        route,
                        CORRELATION_ID);
                return null;
            });
            assertThat(responseWriteStarted.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> second = executor.submit(() -> {
                forwarder.forward(
                        requestWithoutBody("GET"),
                        new MockHttpServletResponse(),
                        route,
                        CORRELATION_ID);
                return null;
            });
            assertThat(second.get(5, TimeUnit.SECONDS)).isNull();

            releaseResponseWrite.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isNull();
            server.verify();
        } finally {
            releaseResponseWrite.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsAForwarderConfigurationWhoseResponseBuffersExceedItsAggregateBudget() {
        GatewayProperties forwarderProperties = new GatewayProperties();
        forwarderProperties.setMaxConcurrentResponseBuffers(2);
        forwarderProperties.setMaxResponseBodyBytes(4);
        forwarderProperties.setMaxResponseBufferBytes(7);

        assertThatThrownBy(() -> new GatewayForwarder(
                        RestClient.builder().build(), forwarderProperties, new SimpleMeterRegistry()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxResponseBufferBytes");
    }

    @Test
    void rejectsASecondResponseWhenResponseBufferCapacityIsFull() throws Exception {
        ForwarderFixture fixture = forwarderFixture(properties ->
                properties.setMaxConcurrentResponseBuffers(1));
        GatewayRoute route = new GatewayRoute(
                "goals", "/api/v1/goals", URI.create(UPSTREAM), false, Set.of());
        MockRestServiceServer server = fixture.server();
        server.expect(requestTo(UPSTREAM + "/api/v1/goals"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        SimpleMeterRegistry registry = fixture.registry();
        GatewayForwarder forwarder = fixture.forwarder();
        CountDownLatch responseWriteStarted = new CountDownLatch(1);
        CountDownLatch releaseResponseWrite = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> first = executor.submit(() -> {
                forwarder.forward(
                        requestWithoutBody("GET"),
                        blockingResponse(responseWriteStarted, releaseResponseWrite),
                        route,
                        CORRELATION_ID);
                return null;
            });
            assertThat(responseWriteStarted.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> forwarder.forward(
                            requestWithoutBody("GET"),
                            new MockHttpServletResponse(),
                            route,
                            CORRELATION_ID))
                    .isInstanceOf(GatewayResponseBufferCapacityException.class);
            assertThat(registry.get("gateway.response.buffer.capacity.rejections")
                    .counter()
                    .count())
                    .isEqualTo(1.0);
            assertThat(registry.get("gateway.response.buffer.available.permits")
                    .gauge()
                    .value())
                    .isEqualTo(0.0);

            releaseResponseWrite.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isNull();
            server.verify();
        } finally {
            releaseResponseWrite.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsAThirdResponseWhenTwoResponseBufferAdmissionsAreFull() throws Exception {
        ForwarderFixture fixture = forwarderFixture(properties -> {
            properties.setMaxConcurrentResponseBuffers(2);
        });
        GatewayRoute route = new GatewayRoute(
                "goals", "/api/v1/goals", URI.create(UPSTREAM), false, Set.of());
        MockRestServiceServer server = fixture.server();
        server.expect(requestTo(UPSTREAM + "/api/v1/goals"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(requestTo(UPSTREAM + "/api/v1/goals"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        SimpleMeterRegistry registry = fixture.registry();
        GatewayForwarder forwarder = fixture.forwarder();
        CountDownLatch responseWritesStarted = new CountDownLatch(2);
        CountDownLatch releaseResponseWrites = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> {
                forwarder.forward(
                        requestWithoutBody("GET"),
                        blockingResponse(responseWritesStarted, releaseResponseWrites),
                        route,
                        CORRELATION_ID);
                return null;
            });
            Future<?> second = executor.submit(() -> {
                forwarder.forward(
                        requestWithoutBody("GET"),
                        blockingResponse(responseWritesStarted, releaseResponseWrites),
                        route,
                        CORRELATION_ID);
                return null;
            });
            assertThat(responseWritesStarted.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> forwarder.forward(
                            requestWithoutBody("GET"),
                            new MockHttpServletResponse(),
                            route,
                            CORRELATION_ID))
                    .isInstanceOf(GatewayResponseBufferCapacityException.class);
            assertThat(registry.get("gateway.response.buffer.capacity.rejections")
                    .counter()
                    .count())
                    .isEqualTo(1.0);
            assertThat(registry.get("gateway.response.buffer.available.permits")
                    .gauge()
                    .value())
                    .isEqualTo(0.0);

            releaseResponseWrites.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isNull();
            assertThat(second.get(5, TimeUnit.SECONDS)).isNull();
            server.verify();
        } finally {
            releaseResponseWrites.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void mapsOversizedUpstreamResponsesToBadGateway() throws Exception {
        properties.setMaxResponseBodyBytes(4);
        upstream.expect(requestTo(UPSTREAM + "/api/v1/goals"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("12345", MediaType.TEXT_PLAIN));

        mockMvc.perform(get("/api/v1/goals"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("UPSTREAM_UNAVAILABLE"));

        assertThat(meterRegistry.get("gateway.upstream.failures")
                .tag("route", "goals")
                .counter()
                .count()).isEqualTo(1);
        upstream.verify();
    }

    @Test
    void mapsUpstreamTimeoutsAndOtherTransportFailures() throws Exception {
        upstream.expect(requestTo(UPSTREAM + "/api/v1/goals"))
                .andRespond(withException(new SocketTimeoutException("timed out")));
        upstream.expect(requestTo(UPSTREAM + "/api/v1/goals"))
                .andRespond(withException(new SocketTimeoutException("timed out")));

        mockMvc.perform(get("/api/v1/goals"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("UPSTREAM_TIMEOUT"));
        upstream.verify();

        upstream.reset();
        upstream.expect(requestTo(UPSTREAM + "/api/v1/goals"))
                .andRespond(withException(new IOException("connection closed")));
        upstream.expect(requestTo(UPSTREAM + "/api/v1/goals"))
                .andRespond(withException(new IOException("connection closed")));

        mockMvc.perform(get("/api/v1/goals"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("UPSTREAM_UNAVAILABLE"));
        assertThat(meterRegistry.get("gateway.upstream.failures")
                .tag("route", "goals")
                .counter()
                .count()).isEqualTo(2);
        upstream.verify();
    }

    @Test
    void mapsUpstreamServerErrorsWithoutRewritingTheirResponse() throws Exception {
        upstream.expect(requestTo(UPSTREAM + "/api/v1/goals"))
                .andRespond(withServerError());
        upstream.expect(requestTo(UPSTREAM + "/api/v1/goals"))
                .andRespond(withServerError());

        mockMvc.perform(get("/api/v1/goals"))
                .andExpect(status().isInternalServerError());

        assertThat(meterRegistry.get("gateway.upstream.failures")
                .tag("route", "goals")
                .counter()
                .count()).isEqualTo(1);
        upstream.verify();
    }

    @Test
    void retriesTransientSafeGetAndReturnsTheRecoveredResponse() throws Exception {
        upstream.expect(requestTo(UPSTREAM + "/api/v1/goals"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        upstream.expect(requestTo(UPSTREAM + "/api/v1/goals"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[{\"id\":\"goal-1\"}]", MediaType.APPLICATION_JSON));

        mockMvc.perform(get("/api/v1/goals"))
                .andExpect(status().isOk())
                .andExpect(content().json("[{\"id\":\"goal-1\"}]"));

        assertThat(meterRegistry.get("gateway.upstream.retry.attempts")
                .tag("route", "goals")
                .tag("failure_class", "upstream_status")
                .counter()
                .count()).isEqualTo(1);
        assertThat(meterRegistry.get("gateway.upstream.failures")
                .tag("route", "goals")
                .counter()
                .count()).isZero();
        upstream.verify();
    }

    @Test
    void retriesTransientTransportFailuresForSafeGets() throws Exception {
        upstream.expect(requestTo(UPSTREAM + "/api/v1/goals"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withException(new SocketTimeoutException("timed out")));
        upstream.expect(requestTo(UPSTREAM + "/api/v1/goals"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        mockMvc.perform(get("/api/v1/goals"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        assertThat(meterRegistry.get("gateway.upstream.retry.attempts")
                .tag("route", "goals")
                .tag("failure_class", "transport")
                .counter()
                .count()).isEqualTo(1);
        upstream.verify();
    }

    @Test
    void doesNotReplayPotentiallyMutatingRequests() throws Exception {
        properties.getUpstream().getRetry().setMaxAttempts(5);
        upstream.expect(requestTo(UPSTREAM + "/api/v1/goals"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        mockMvc.perform(post("/api/v1/goals")
                        .header("Idempotency-Key", "caller-supplied-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"must not duplicate\"}"))
                .andExpect(status().isServiceUnavailable());

        assertThat(meterRegistry.get("gateway.upstream.retry.skipped")
                .tag("route", "goals")
                .tag("reason", "unsafe_method")
                .counter()
                .count()).isEqualTo(1);
        upstream.verify();
    }

    @Test
    void doesNotReplayPostAfterAnAmbiguousTransportFailure() throws Exception {
        properties.getUpstream().getRetry().setMaxAttempts(5);
        upstream.expect(requestTo(UPSTREAM + "/api/v1/goals"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withException(new SocketTimeoutException("upstream outcome is ambiguous")));

        mockMvc.perform(post("/api/v1/goals")
                        .header("Idempotency-Key", "caller-supplied-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"must not duplicate\"}"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("UPSTREAM_TIMEOUT"));

        assertThat(meterRegistry.get("gateway.upstream.retry.skipped")
                .tag("route", "goals")
                .tag("reason", "unsafe_method")
                .counter()
                .count()).isEqualTo(1);
        upstream.verify();
    }

    @Test
    void rejectsUnsupportedMethodsWithAllowHeader() throws Exception {
        mockMvc.perform(request("CONNECT", URI.create("/api/v1/goals")))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(HttpHeaders.ALLOW,
                        "GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS"))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void stripsHopByHopAndCallerSuppliedRoutingHeadersAndSetsTrustedForwardingValues() throws Exception {
        upstream.expect(requestTo(UPSTREAM + "/api/v1/goals"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Forwarded-For", "127.0.0.1"))
                .andExpect(header("X-Forwarded-Proto", "http"))
                .andExpect(header("X-Forwarded-Host", "localhost"))
                .andExpect(headerDoesNotExist("Connection"))
                .andExpect(headerDoesNotExist("Forwarded"))
                .andExpect(headerDoesNotExist("X-Real-IP"))
                .andExpect(headerDoesNotExist("X-HTTP-Method-Override"))
                .andExpect(headerDoesNotExist("X-Method-Override"))
                .andExpect(headerDoesNotExist("X-Original-URL"))
                .andExpect(headerDoesNotExist("X-Rewrite-URL"))
                .andExpect(headerDoesNotExist("X-LifeOS-Authenticated-Account-Id"))
                .andExpect(headerDoesNotExist("X-LifeOS-Authenticated-Session-Id"))
                .andExpect(headerDoesNotExist("X-LifeOS-Authentication-Method"))
                .andExpect(headerDoesNotExist("X-LifeOS-Workload-Identity"))
                .andExpect(headerDoesNotExist("X-LifeOS-Workload-Token"))
                .andExpect(headerDoesNotExist("X-Forwarded-Port"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        mockMvc.perform(get("/api/v1/goals")
                        .header("Connection", "keep-alive")
                        .header("Forwarded", "for=attacker")
                        .header("X-Real-IP", "192.0.2.10")
                        .header("X-HTTP-Method-Override", "DELETE")
                        .header("X-Method-Override", "DELETE")
                        .header("X-Original-URL", "/admin")
                        .header("X-Rewrite-URL", "/admin")
                        .header("X-LifeOS-Authenticated-Account-Id", "attacker-account")
                        .header("X-LifeOS-Authenticated-Session-Id", "attacker-session")
                        .header("X-LifeOS-Authentication-Method", "attacker-method")
                        .header("X-LifeOS-Workload-Identity", "attacker-workload")
                        .header("X-LifeOS-Workload-Token", "attacker-token")
                        .header("X-Forwarded-For", "192.0.2.10")
                        .header("X-Forwarded-Host", "attacker.test")
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-Port", "8443"))
                .andExpect(status().isOk());

        upstream.verify();
    }

    @Test
    void doesNotWriteAHeadResponseBody() throws Exception {
        upstream.expect(requestTo(UPSTREAM + "/api/v1/goals"))
                .andExpect(method(HttpMethod.HEAD))
                .andRespond(withSuccess("body must not be returned", MediaType.TEXT_PLAIN));

        mockMvc.perform(head("/api/v1/goals"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));

        upstream.verify();
    }

    @Test
    void stripsTheServletContextPathBeforeResolvingAndForwarding() throws Exception {
        upstream.expect(requestTo(UPSTREAM + "/api/v1/goals"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        mockMvc.perform(get("/gateway/api/v1/goals").contextPath("/gateway"))
                .andExpect(status().isOk());

        upstream.verify();
    }

    @Test
    void returnsControlledBadRequestForAnUnparsableRawQuery() throws Exception {
        mockMvc.perform(get("/api/v1/goals").with(request -> {
                    request.setQueryString("invalid query");
                    return request;
                }))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_TARGET"));
    }

    @Test
    void returnsControlledNotFoundWithoutForwardingUnknownRoutes() throws Exception {
        mockMvc.perform(get("/api/v1/internal/authorization/decisions"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("ROUTE_NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("The requested API route does not exist."));
    }

    @Test
    void relaysTheExactNotificationSseRouteWithoutUsingTheBufferedResponseLimit() throws Exception {
        StreamingControllerFixture fixture = useStreamingRoutes();
        properties.setMaxResponseBodyBytes(4);
        String streamBody = "data: " + "x".repeat(64) + "\n\n";
        HttpHeaders upstreamHeaders = new HttpHeaders();
        upstreamHeaders.set("X-Upstream", "notification");
        upstreamHeaders.set("X-Correlation-ID", "22222222-2222-4222-8222-222222222222");
        upstreamHeaders.set("Connection", "close");

        upstream.expect(requestTo(NOTIFICATION_UPSTREAM + GatewayRoute.NOTIFICATION_STREAM_PATH))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer stream-token"))
                .andExpect(header("Last-Event-ID", "41"))
                .andExpect(header("X-Correlation-ID", CORRELATION_ID))
                .andExpect(header(GatewayAuthenticatedSubject.ACCOUNT_ID_HEADER, fixture.subject().accountId().toString()))
                .andRespond(withSuccess(streamBody, MediaType.TEXT_EVENT_STREAM).headers(upstreamHeaders));

        mockMvc.perform(get(GatewayRoute.NOTIFICATION_STREAM_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer stream-token")
                        .header("Last-Event-ID", "41")
                        .header("X-Correlation-ID", CORRELATION_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(header().string("X-Upstream", "notification"))
                .andExpect(header().string("X-Correlation-ID", CORRELATION_ID))
                .andExpect(header().doesNotExist("Connection"))
                .andExpect(content().string(streamBody));

        assertThat(fixture.rateLimitChecks().get()).isEqualTo(2);
        verify(fixture.authenticationService()).authenticate(any(), any());
        assertThat(meterRegistry.get("gateway.streaming.available.permits").gauge().value()).isEqualTo(32.0);
        upstream.verify();
    }

    @Test
    void rejectsNonGetMethodsAndPathDescendantsForTheNotificationSseRoute() throws Exception {
        StreamingControllerFixture fixture = useStreamingRoutes();

        mockMvc.perform(post(GatewayRoute.NOTIFICATION_STREAM_PATH))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(HttpHeaders.ALLOW, "GET"))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        mockMvc.perform(get(GatewayRoute.NOTIFICATION_STREAM_PATH + "/unexpected"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROUTE_NOT_FOUND"));

        assertThat(fixture.rateLimitChecks().get()).isZero();
        verifyNoInteractions(fixture.authenticationService());
        upstream.verify();
    }

    @Test
    void mapsAStreamingUpstreamTransportFailureWithoutRetryingTheLiveConnection() throws Exception {
        StreamingControllerFixture fixture = useStreamingRoutes();
        upstream.expect(requestTo(NOTIFICATION_UPSTREAM + GatewayRoute.NOTIFICATION_STREAM_PATH))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withException(new IOException("connection closed")));

        mockMvc.perform(get(GatewayRoute.NOTIFICATION_STREAM_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer stream-token"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("UPSTREAM_UNAVAILABLE"));

        assertThat(fixture.rateLimitChecks().get()).isEqualTo(2);
        verify(fixture.authenticationService()).authenticate(any(), any());
        upstream.verify();
    }

    @Test
    void keepsNotificationHistoryOnTheOrdinaryBoundedBufferedForwarder() throws Exception {
        useStreamingRoutes();
        properties.setMaxResponseBodyBytes(4);
        upstream.expect(requestTo(NOTIFICATION_UPSTREAM + "/api/v1/notifications"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("12345", MediaType.APPLICATION_JSON));

        mockMvc.perform(get("/api/v1/notifications")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer history-token"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("UPSTREAM_UNAVAILABLE"));

        upstream.verify();
    }

    @Test
    void isolatesAFullLiveStreamRouteBulkheadFromBufferedNotificationHistory() throws Exception {
        GatewayProperties isolationProperties = new GatewayProperties();
        isolationProperties.getStreaming().setMaxConcurrentConnections(1);
        isolationProperties.getUpstream().getBulkhead().setMaxConcurrentRequests(1);
        GatewayProperties.Route stream = notificationStreamRoute();
        GatewayProperties.Route history = new GatewayProperties.Route(
                "notification-history", "/api/v1/notifications", NOTIFICATION_UPSTREAM, false);
        isolationProperties.setRoutes(List.of(stream, history));
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GatewayForwarder forwarder = new GatewayForwarder(builder.build(), isolationProperties, registry);
        GatewayRouteTable routeTable = new GatewayRouteTable(isolationProperties);
        GatewayRoute streamRoute = routeTable.resolve(GatewayRoute.NOTIFICATION_STREAM_PATH).orElseThrow();
        GatewayRoute historyRoute = routeTable.resolve("/api/v1/notifications").orElseThrow();
        CountDownLatch streamReadStarted = new CountDownLatch(1);
        CountDownLatch releaseStream = new CountDownLatch(1);
        server.expect(requestTo(NOTIFICATION_UPSTREAM + GatewayRoute.NOTIFICATION_STREAM_PATH))
                .andExpect(method(HttpMethod.GET))
                .andRespond(request -> blockingSseResponse(streamReadStarted, releaseStream));
        server.expect(requestTo(NOTIFICATION_UPSTREAM + "/api/v1/notifications"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> activeStream = executor.submit(() -> {
                forwarder.forward(
                        requestWithoutBody("GET", GatewayRoute.NOTIFICATION_STREAM_PATH),
                        new MockHttpServletResponse(),
                        streamRoute,
                        CORRELATION_ID);
                return null;
            });
            assertThat(streamReadStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(registry.get("gateway.streaming.available.permits").gauge().value()).isZero();

            MockHttpServletResponse historyResponse = new MockHttpServletResponse();
            forwarder.forward(
                    requestWithoutBody("GET", "/api/v1/notifications"),
                    historyResponse,
                    historyRoute,
                    CORRELATION_ID);

            assertThat(historyResponse.getStatus()).isEqualTo(HttpStatus.OK.value());
            assertThat(activeStream.isDone()).isFalse();
            releaseStream.countDown();
            assertThat(activeStream.get(5, TimeUnit.SECONDS)).isNull();
            server.verify();
        } finally {
            releaseStream.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void relaysTheExactMediaSourceUploadWithoutUsingTheOrdinaryRequestBuffer() throws Exception {
        GatewayProperties mediaProperties = new GatewayProperties();
        mediaProperties.setMaxRequestBodyBytes(4);
        mediaProperties.setRoutes(List.of(mediaAssetsRoute()));
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GatewayForwarder forwarder = new GatewayForwarder(builder.build(), mediaProperties, registry);
        String assetId = "11111111-1111-4111-8111-111111111111";
        String uploadPath = GatewayRoute.MEDIA_ASSETS_PATH_PREFIX + "/" + assetId + "/source";
        GatewayRoute route = new GatewayRouteTable(mediaProperties).resolve(uploadPath).orElseThrow();
        byte[] multipartBody = "12345".getBytes(StandardCharsets.UTF_8);
        HttpServletRequest request = requestWithBody("PUT", uploadPath, multipartBody);
        String contentType = "multipart/form-data; boundary=lifeos-media-boundary";
        when(request.getHeaderNames()).thenReturn(Collections.enumeration(List.of(
                HttpHeaders.CONTENT_TYPE, HttpHeaders.AUTHORIZATION, "Idempotency-Key", HttpHeaders.IF_MATCH)));
        when(request.getHeaders(HttpHeaders.CONTENT_TYPE)).thenReturn(Collections.enumeration(List.of(contentType)));
        when(request.getHeaders(HttpHeaders.AUTHORIZATION))
                .thenReturn(Collections.enumeration(List.of("Bearer media-access-token")));
        when(request.getHeaders("Idempotency-Key"))
                .thenReturn(Collections.enumeration(List.of("media-upload-idempotency-key")));
        when(request.getHeaders(HttpHeaders.IF_MATCH)).thenReturn(Collections.enumeration(List.of("\"0\"")));

        server.expect(requestTo(MEDIA_UPSTREAM + uploadPath))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header(HttpHeaders.CONTENT_TYPE, contentType))
                .andExpect(header(HttpHeaders.CONTENT_LENGTH, Integer.toString(multipartBody.length)))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer media-access-token"))
                .andExpect(header("Idempotency-Key", "media-upload-idempotency-key"))
                .andExpect(header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers
                        .content().bytes(multipartBody))
                .andRespond(withSuccess("{\"state\":\"STORED_AWAITING_EXTERNAL_PROCESSING\"}", MediaType.APPLICATION_JSON));

        MockHttpServletResponse response = new MockHttpServletResponse();
        forwarder.forward(request, response, route, CORRELATION_ID);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(response.getContentAsString()).contains("STORED_AWAITING_EXTERNAL_PROCESSING");
        assertThat(registry.get("gateway.media.upload.available.permits").gauge().value()).isEqualTo(4.0);
        assertThat(registry.get("gateway.media.upload.inflight").gauge().value()).isZero();
        assertThat(registry.find("gateway.upstream.retry.attempts").counters()).isEmpty();
        server.verify();
    }

    @Test
    void doesNotOpenTheMediaUploadCircuitWhenTheInboundClientAbortsItsBody() throws Exception {
        GatewayProperties mediaProperties = new GatewayProperties();
        mediaProperties.getUpstream().getCircuitBreaker().setFailureThreshold(1);
        mediaProperties.setRoutes(List.of(mediaAssetsRoute()));
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GatewayForwarder forwarder = new GatewayForwarder(builder.build(), mediaProperties, registry);
        String assetId = "11111111-1111-4111-8111-111111111111";
        String uploadPath = GatewayRoute.MEDIA_ASSETS_PATH_PREFIX + "/" + assetId + "/source";
        GatewayRoute route = new GatewayRouteTable(mediaProperties).resolve(uploadPath).orElseThrow();
        HttpServletRequest abortedRequest = requestWithoutBody("PUT", uploadPath);
        when(abortedRequest.getContentLengthLong()).thenReturn(-1L);
        when(abortedRequest.getInputStream()).thenReturn(abortingInputStream());

        server.expect(requestTo(MEDIA_UPSTREAM + uploadPath))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess());

        assertThatThrownBy(() -> forwarder.forward(
                        abortedRequest, new MockHttpServletResponse(), route, CORRELATION_ID))
                .isInstanceOf(GatewayBadRequestException.class);
        assertThat(registry.get("gateway.upstream.failures")
                        .tag("route", "media-assets-media-upload")
                        .counter()
                        .count())
                .isZero();
        assertThat(registry.get("gateway.media.upload.available.permits").gauge().value()).isEqualTo(4.0);

        server.reset();
        server.expect(requestTo(MEDIA_UPSTREAM + uploadPath))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess("{\"state\":\"STORED_AWAITING_EXTERNAL_PROCESSING\"}", MediaType.APPLICATION_JSON));
        forwarder.forward(
                requestWithBody("PUT", uploadPath, new byte[] {1}),
                new MockHttpServletResponse(),
                route,
                CORRELATION_ID);

        server.verify();
    }

    @Test
    void relaysExactMediaHlsReadsOutsideTheOrdinaryResponseBuffer() throws Exception {
        GatewayProperties mediaProperties = new GatewayProperties();
        mediaProperties.setMaxResponseBodyBytes(4);
        mediaProperties.setRoutes(List.of(mediaAssetsRoute()));
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GatewayForwarder forwarder = new GatewayForwarder(builder.build(), mediaProperties, registry);
        String assetId = "11111111-1111-4111-8111-111111111111";
        String hlsPath = GatewayRoute.MEDIA_ASSETS_PATH_PREFIX + "/" + assetId + "/hls/segments/segment-001.m4s";
        GatewayRoute route = new GatewayRouteTable(mediaProperties).resolve(hlsPath).orElseThrow();
        HttpServletRequest request = requestWithoutBody("GET", hlsPath);
        when(request.getHeaderNames()).thenReturn(Collections.enumeration(List.of(HttpHeaders.AUTHORIZATION)));
        when(request.getHeaders(HttpHeaders.AUTHORIZATION))
                .thenReturn(Collections.enumeration(List.of("Bearer media-access-token")));

        server.expect(requestTo(MEDIA_UPSTREAM + hlsPath))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer media-access-token"))
                .andRespond(withSuccess("12345", MediaType.parseMediaType("video/iso.segment"))
                        .header(HttpHeaders.CACHE_CONTROL, "private, no-store"));

        MockHttpServletResponse response = new MockHttpServletResponse();
        forwarder.forward(request, response, route, CORRELATION_ID);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(response.getContentAsString()).isEqualTo("12345");
        assertThat(response.getContentType()).isEqualTo("video/iso.segment");
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("private, no-store");
        assertThat(registry.get("gateway.media.hls.available.permits").gauge().value()).isEqualTo(8.0);
        assertThat(registry.get("gateway.media.hls.inflight").gauge().value()).isZero();
        assertThat(registry.find("gateway.upstream.retry.attempts").counters()).isEmpty();
        server.verify();
    }

    @Test
    void relaysAssistantProviderTimeoutThroughDedicatedClientWithoutRetrying() throws Exception {
        GatewayProperties assistantProperties = new GatewayProperties();
        assistantProperties.setRoutes(List.of(assistantRoute()));
        RestClient.Builder bufferedBuilder = RestClient.builder();
        MockRestServiceServer bufferedServer = MockRestServiceServer.bindTo(bufferedBuilder).build();
        RestClient bufferedClient = bufferedBuilder.build();
        RestClient.Builder assistantBuilder = RestClient.builder();
        MockRestServiceServer assistantServer = MockRestServiceServer.bindTo(assistantBuilder).build();
        RestClient assistantClient = assistantBuilder.build();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GatewayForwarder forwarder = new GatewayForwarder(
                bufferedClient,
                bufferedClient,
                bufferedClient,
                bufferedClient,
                bufferedClient,
                assistantClient,
                assistantProperties,
                registry,
                new GatewayUpstreamResilience(assistantProperties, registry),
                new GatewayRetryPolicy(assistantProperties));
        GatewayRoute route = new GatewayRouteTable(assistantProperties)
                .resolve("/api/v1/assistant/conversations/11111111-1111-4111-8111-111111111111")
                .orElseThrow();
        String requestPath = "/api/v1/assistant/conversations/11111111-1111-4111-8111-111111111111";

        assistantServer.expect(requestTo(AI_ASSISTANT_UPSTREAM + requestPath))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.GATEWAY_TIMEOUT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"AI_PROVIDER_TIMEOUT\",\"retryable\":true}"));

        MockHttpServletResponse response = new MockHttpServletResponse();
        forwarder.forward(requestWithoutBody("GET", requestPath), response, route, CORRELATION_ID);

        assertThat(route.requiresAuthentication(requestPath, "GET")).isTrue();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT.value());
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_JSON_VALUE);
        assertThat(response.getContentAsString()).contains("AI_PROVIDER_TIMEOUT");
        assertThat(registry.find("gateway.upstream.retry.attempts").counters()).isEmpty();
        assertThat(registry.find("gateway.upstream.retry.skipped").counters()).isEmpty();
        assistantServer.verify();
        bufferedServer.verify();
    }

    @Test
    void forwardsAssistantAuthenticationCorrelationAndTraceHeadersToTheDedicatedRoute() throws Exception {
        GatewayProperties assistantProperties = new GatewayProperties();
        assistantProperties.setRoutes(List.of(assistantRoute()));
        RestClient.Builder bufferedBuilder = RestClient.builder();
        MockRestServiceServer bufferedServer = MockRestServiceServer.bindTo(bufferedBuilder).build();
        RestClient.Builder assistantBuilder = RestClient.builder();
        MockRestServiceServer assistantServer = MockRestServiceServer.bindTo(assistantBuilder).build();
        GatewayForwarder forwarder = new GatewayForwarder(
                bufferedBuilder.build(),
                bufferedBuilder.build(),
                bufferedBuilder.build(),
                bufferedBuilder.build(),
                bufferedBuilder.build(),
                assistantBuilder.build(),
                assistantProperties,
                new SimpleMeterRegistry(),
                new GatewayUpstreamResilience(assistantProperties, new SimpleMeterRegistry()),
                new GatewayRetryPolicy(assistantProperties));
        String requestPath = "/api/v1/assistant/conversations/11111111-1111-4111-8111-111111111111";
        GatewayRoute route = new GatewayRouteTable(assistantProperties).resolve(requestPath).orElseThrow();
        HttpServletRequest request = requestWithoutBody("GET", requestPath);
        when(request.getHeaderNames()).thenReturn(Collections.enumeration(
                List.of(HttpHeaders.AUTHORIZATION, "traceparent", CorrelationIdSupport.HEADER_NAME)));
        when(request.getHeaders(HttpHeaders.AUTHORIZATION))
                .thenReturn(Collections.enumeration(List.of("Bearer assistant-access-token")));
        when(request.getHeaders("traceparent"))
                .thenReturn(Collections.enumeration(List.of("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")));
        when(request.getHeaders(CorrelationIdSupport.HEADER_NAME))
                .thenReturn(Collections.enumeration(List.of(CORRELATION_ID)));

        assistantServer.expect(requestTo(AI_ASSISTANT_UPSTREAM + requestPath))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer assistant-access-token"))
                .andExpect(header("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"))
                .andExpect(header(CorrelationIdSupport.HEADER_NAME, CORRELATION_ID))
                .andRespond(withSuccess("{\"status\":\"ok\"}", MediaType.APPLICATION_JSON));

        MockHttpServletResponse response = new MockHttpServletResponse();
        forwarder.forward(request, response, route, CORRELATION_ID);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(response.getContentAsString()).contains("\"status\":\"ok\"");
        assistantServer.verify();
        bufferedServer.verify();
    }

    @Test
    void rejectsRequestBodyFramingForTheExactMediaHlsResponseOnlyRoute() throws Exception {
        GatewayProperties mediaProperties = new GatewayProperties();
        mediaProperties.setRoutes(List.of(mediaAssetsRoute()));
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GatewayForwarder forwarder = new GatewayForwarder(
                builder.build(), mediaProperties, new SimpleMeterRegistry());
        String assetId = "11111111-1111-4111-8111-111111111111";
        String hlsPath = GatewayRoute.MEDIA_ASSETS_PATH_PREFIX + "/" + assetId + "/hls/master.m3u8";
        GatewayRoute route = new GatewayRouteTable(mediaProperties).resolve(hlsPath).orElseThrow();

        assertThatThrownBy(() -> forwarder.forward(
                        requestWithBody("GET", hlsPath, new byte[] {1}),
                        new MockHttpServletResponse(),
                        route,
                        CORRELATION_ID))
                .isInstanceOf(GatewayBadRequestException.class);

        HttpServletRequest chunkedRequest = requestWithoutBody("GET", hlsPath);
        when(chunkedRequest.getHeader(HttpHeaders.TRANSFER_ENCODING)).thenReturn("chunked");
        assertThatThrownBy(() -> forwarder.forward(
                        chunkedRequest, new MockHttpServletResponse(), route, CORRELATION_ID))
                .isInstanceOf(GatewayBadRequestException.class);

        server.verify();
    }

    @Test
    void isolatesAFullMediaHlsAdmissionFromOrdinaryMediaSessionMetadata() throws Exception {
        GatewayProperties mediaProperties = new GatewayProperties();
        mediaProperties.getMediaHls().setMaxConcurrentStreams(1);
        mediaProperties.getUpstream().getBulkhead().setMaxConcurrentRequests(1);
        GatewayProperties.Route assets = mediaAssetsRoute();
        GatewayProperties.Route sessions = new GatewayProperties.Route(
                "media-sessions", "/api/v1/media/sessions", MEDIA_UPSTREAM, true);
        mediaProperties.setRoutes(List.of(assets, sessions));
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GatewayForwarder forwarder = new GatewayForwarder(builder.build(), mediaProperties, registry);
        String assetId = "11111111-1111-4111-8111-111111111111";
        String hlsPath = GatewayRoute.MEDIA_ASSETS_PATH_PREFIX + "/" + assetId + "/hls/master.m3u8";
        GatewayRouteTable routeTable = new GatewayRouteTable(mediaProperties);
        GatewayRoute hlsRoute = routeTable.resolve(hlsPath).orElseThrow();
        GatewayRoute sessionsRoute = routeTable.resolve("/api/v1/media/sessions").orElseThrow();
        CountDownLatch hlsReadStarted = new CountDownLatch(1);
        CountDownLatch releaseHls = new CountDownLatch(1);
        server.expect(requestTo(MEDIA_UPSTREAM + hlsPath))
                .andExpect(method(HttpMethod.GET))
                .andRespond(request -> blockingMediaHlsResponse(hlsReadStarted, releaseHls));
        server.expect(requestTo(MEDIA_UPSTREAM + "/api/v1/media/sessions"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> activeHls = executor.submit(() -> {
                forwarder.forward(
                        requestWithoutBody("GET", hlsPath),
                        new MockHttpServletResponse(),
                        hlsRoute,
                        CORRELATION_ID);
                return null;
            });
            assertThat(hlsReadStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(registry.get("gateway.media.hls.available.permits").gauge().value()).isZero();

            MockHttpServletResponse sessionsResponse = new MockHttpServletResponse();
            forwarder.forward(
                    requestWithoutBody("GET", "/api/v1/media/sessions"),
                    sessionsResponse,
                    sessionsRoute,
                    CORRELATION_ID);

            assertThat(sessionsResponse.getStatus()).isEqualTo(HttpStatus.OK.value());
            assertThat(activeHls.isDone()).isFalse();
            releaseHls.countDown();
            assertThat(activeHls.get(5, TimeUnit.SECONDS)).isNull();
            server.verify();
        } finally {
            releaseHls.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void keepsNonExactMediaPathsOnTheOrdinaryBoundedProxy() throws Exception {
        GatewayProperties mediaProperties = new GatewayProperties();
        mediaProperties.setMaxRequestBodyBytes(4);
        mediaProperties.setRoutes(List.of(mediaAssetsRoute()));
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GatewayForwarder forwarder = new GatewayForwarder(
                builder.build(), mediaProperties, new SimpleMeterRegistry());
        String assetId = "11111111-1111-4111-8111-111111111111";
        String descendant = GatewayRoute.MEDIA_ASSETS_PATH_PREFIX + "/" + assetId + "/source/child";
        GatewayRoute route = new GatewayRouteTable(mediaProperties).resolve(descendant).orElseThrow();

        assertThatThrownBy(() -> forwarder.forward(
                        requestWithBody("PUT", descendant, "12345".getBytes(StandardCharsets.UTF_8)),
                        new MockHttpServletResponse(),
                        route,
                        CORRELATION_ID))
                .isInstanceOf(GatewayPayloadTooLargeException.class);
        server.verify();
    }

    private void useProtectedRoute() {
        useProtectedRoute(Set.of());
    }

    private static HttpServletRequest requestWithoutBody(String method) {
        return requestWithoutBody(method, "/api/v1/goals");
    }

    private static HttpServletRequest requestWithoutBody(String method, String requestUri) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getRequestURI()).thenReturn(requestUri);
        when(request.getContextPath()).thenReturn("");
        when(request.getQueryString()).thenReturn(null);
        when(request.getContentLengthLong()).thenReturn(0L);
        when(request.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getScheme()).thenReturn("http");
        when(request.getServerName()).thenReturn("localhost");
        return request;
    }

    private static HttpServletRequest requestWithBody(String method, byte[] body) throws IOException {
        return requestWithBody(method, "/api/v1/goals", body);
    }

    private static HttpServletRequest requestWithBody(String method, String requestUri, byte[] body)
            throws IOException {
        HttpServletRequest request = requestWithoutBody(method, requestUri);
        when(request.getContentLengthLong()).thenReturn((long) body.length);
        when(request.getInputStream()).thenReturn(bodyInputStream(body));
        return request;
    }

    private static ServletInputStream bodyInputStream(byte[] body) {
        ByteArrayInputStream input = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public int read() {
                return input.read();
            }

            @Override
            public boolean isFinished() {
                return input.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                // Servlet async callbacks are not used by this synchronous test.
            }
        };
    }

    private static ServletInputStream abortingInputStream() {
        return new ServletInputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("client disconnected");
            }

            @Override
            public boolean isFinished() {
                return false;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                // Servlet async callbacks are not used by this synchronous test.
            }
        };
    }

    private static HttpServletResponse blockingResponse(
            CountDownLatch writeStarted, CountDownLatch releaseWrite) throws IOException {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getOutputStream()).thenReturn(blockingOutputStream(writeStarted, releaseWrite));
        return response;
    }

    private static ServletOutputStream blockingOutputStream(
            CountDownLatch writeStarted, CountDownLatch releaseWrite) {
        return new ServletOutputStream() {
            @Override
            public void write(int value) throws IOException {
                writeStarted.countDown();
                awaitLatch(releaseWrite, "response write release");
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener writeListener) {
                // Servlet async callbacks are not used by this synchronous test.
            }
        };
    }

    private static void awaitLatch(CountDownLatch latch, String description) throws IOException {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IOException("timed out waiting for " + description);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for " + description, exception);
        }
    }

    private static ServletInputStream stalledInputStream(
            CountDownLatch bodyReadStarted, CountDownLatch releaseBody) {
        return new ServletInputStream() {
            @Override
            public int read() throws IOException {
                bodyReadStarted.countDown();
                try {
                    if (!releaseBody.await(5, TimeUnit.SECONDS)) {
                        throw new IOException("timed out waiting for test body release");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while waiting for test body release", exception);
                }
                return -1;
            }

            @Override
            public boolean isFinished() {
                return false;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                // Servlet async callbacks are not used by this synchronous test.
            }
        };
    }

    private static ForwarderFixture forwarderFixture(Consumer<GatewayProperties> customize) {
        GatewayProperties properties = new GatewayProperties();
        properties.setRoutes(List.of(new GatewayProperties.Route(
                "goals", "/api/v1/goals", UPSTREAM, false)));
        customize.accept(properties);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GatewayForwarder forwarder = new GatewayForwarder(builder.build(), properties, registry);
        return new ForwarderFixture(properties, server, registry, forwarder);
    }

    private MockMvc mockMvcForUpstreamFailure(String failureClass, int retryAfterSeconds) throws IOException {
        GatewayForwarder forwarder = mock(GatewayForwarder.class);
        doThrow(GatewayUpstreamException.serviceUnavailable(failureClass, retryAfterSeconds))
                .when(forwarder)
                .forward(any(), any(), any(), any(), any());
        return MockMvcBuilders.standaloneSetup(new GatewayController(
                        new GatewayRouteTable(properties),
                        forwarder,
                        mock(GatewayAuthenticationService.class),
                        (ignoredRoute, ignoredRequest, ignoredSubject) -> {}))
                .addFilters(new CorrelationIdFilter())
                .build();
    }

    private StreamingControllerFixture useStreamingRoutes() {
        GatewayProperties.Route stream = notificationStreamRoute();
        GatewayProperties.Route history = new GatewayProperties.Route(
                "notification-history", "/api/v1/notifications", NOTIFICATION_UPSTREAM, true);
        GatewayProperties.Route endpoints = new GatewayProperties.Route(
                "notification-endpoints", "/api/v1/notification-endpoints", NOTIFICATION_UPSTREAM, true);
        properties.setRoutes(List.of(stream, history, endpoints));

        RestClient.Builder upstreamBuilder = RestClient.builder();
        upstream = MockRestServiceServer.bindTo(upstreamBuilder).build();
        GatewayForwarder forwarder = new GatewayForwarder(upstreamBuilder.build(), properties, meterRegistry);
        GatewayAuthenticationService authenticationService = mock(GatewayAuthenticationService.class);
        GatewayAuthenticatedSubject subject = new GatewayAuthenticatedSubject(
                UUID.randomUUID(), UUID.randomUUID(), "PASSWORD");
        when(authenticationService.authenticate(any(), any())).thenReturn(subject);
        AtomicInteger rateLimitChecks = new AtomicInteger();
        GatewayRateLimiter rateLimiter = (ignoredRoute, ignoredRequest, ignoredSubject) -> rateLimitChecks.incrementAndGet();
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new GatewayController(
                                new GatewayRouteTable(properties), forwarder, authenticationService, rateLimiter))
                .addFilters(new CorrelationIdFilter())
                .build();
        return new StreamingControllerFixture(authenticationService, subject, rateLimitChecks);
    }

    private static GatewayProperties.Route notificationStreamRoute() {
        GatewayProperties.Route stream = new GatewayProperties.Route(
                "notification-stream",
                GatewayRoute.NOTIFICATION_STREAM_PATH,
                NOTIFICATION_UPSTREAM,
                true);
        stream.setAuthenticationRequiredMethods(Set.of("GET"));
        stream.setStreaming(true);
        return stream;
    }

    private static GatewayProperties.Route documentUploadRoute() {
        GatewayProperties.Route documents = new GatewayProperties.Route(
                "document-vault", GatewayRoute.DOCUMENT_UPLOAD_PATH, "https://documents.test", true);
        documents.setDocumentUploadStreaming(true);
        return documents;
    }

    private static GatewayProperties.Route mediaAssetsRoute() {
        GatewayProperties.Route media = new GatewayProperties.Route(
                "media-assets", GatewayRoute.MEDIA_ASSETS_PATH_PREFIX, MEDIA_UPSTREAM, true);
        media.setMediaUploadStreaming(true);
        media.setMediaHlsStreaming(true);
        return media;
    }

    private static GatewayProperties.Route assistantRoute() {
        return new GatewayProperties.Route(
                "ai-assistant", "/api/v1/assistant", AI_ASSISTANT_UPSTREAM, true);
    }

    private static ClientHttpResponse blockingSseResponse(
            CountDownLatch streamReadStarted, CountDownLatch releaseStream) {
        return new ClientHttpResponse() {
            private final HttpHeaders headers = new HttpHeaders();

            {
                headers.setContentType(MediaType.TEXT_EVENT_STREAM);
            }

            @Override
            public HttpStatus getStatusCode() {
                return HttpStatus.OK;
            }

            @Override
            public String getStatusText() {
                return HttpStatus.OK.getReasonPhrase();
            }

            @Override
            public void close() {
                // The forwarder closes the input stream when a downstream client disconnects.
            }

            @Override
            public InputStream getBody() {
                return new InputStream() {
                    private final byte[] heartbeat = ": heartbeat\n\n".getBytes(StandardCharsets.UTF_8);
                    private int nextByte;
                    private boolean waiting;

                    @Override
                    public int read() throws IOException {
                        if (!waiting) {
                            waiting = true;
                            streamReadStarted.countDown();
                            awaitLatch(releaseStream, "stream release");
                        }
                        return nextByte < heartbeat.length ? heartbeat[nextByte++] & 0xff : -1;
                    }
                };
            }

            @Override
            public HttpHeaders getHeaders() {
                return headers;
            }
        };
    }

    private static ClientHttpResponse blockingMediaHlsResponse(
            CountDownLatch bodyReadStarted, CountDownLatch releaseBody) {
        return new ClientHttpResponse() {
            private final HttpHeaders headers = new HttpHeaders();

            {
                headers.setContentType(MediaType.parseMediaType("application/vnd.apple.mpegurl"));
                headers.setCacheControl("private, no-store");
            }

            @Override
            public HttpStatus getStatusCode() {
                return HttpStatus.OK;
            }

            @Override
            public String getStatusText() {
                return HttpStatus.OK.getReasonPhrase();
            }

            @Override
            public void close() {
                // The forwarder closes the input stream when the downstream relay ends.
            }

            @Override
            public InputStream getBody() {
                return new InputStream() {
                    private boolean waiting = true;

                    @Override
                    public int read() throws IOException {
                        if (waiting) {
                            waiting = false;
                            bodyReadStarted.countDown();
                            awaitLatch(releaseBody, "Media HLS release");
                        }
                        return -1;
                    }
                };
            }

            @Override
            public HttpHeaders getHeaders() {
                return headers;
            }
        };
    }

    private record ForwarderFixture(
            GatewayProperties properties,
            MockRestServiceServer server,
            SimpleMeterRegistry registry,
            GatewayForwarder forwarder) {
    }

    private record StreamingControllerFixture(
            GatewayAuthenticationService authenticationService,
            GatewayAuthenticatedSubject subject,
            AtomicInteger rateLimitChecks) {
    }

    private void useProtectedRoute(Set<String> authenticationRequiredMethods) {
        GatewayProperties.Route route = new GatewayProperties.Route("goals", "/api/v1/goals", UPSTREAM, true);
        route.setAuthenticationRequiredMethods(authenticationRequiredMethods);
        properties.setRoutes(List.of(
                route));
        GatewayAuthenticationProperties authenticationProperties = configuredAuthenticationProperties();

        RestClient.Builder identityBuilder = RestClient.builder()
                .baseUrl(authenticationProperties.getBaseUrl());
        identity = MockRestServiceServer.bindTo(identityBuilder).build();
        GatewayAuthenticationClient authenticationClient = new GatewayAuthenticationClient(
                identityBuilder.build(), authenticationProperties);
        GatewayAuthenticationService authenticationService = new GatewayAuthenticationService(
                authenticationClient, new GatewayAuthenticationMetrics(meterRegistry), authenticationProperties);

        RestClient.Builder upstreamBuilder = RestClient.builder();
        upstream = MockRestServiceServer.bindTo(upstreamBuilder).build();
        GatewayForwarder forwarder = new GatewayForwarder(upstreamBuilder.build(), properties, meterRegistry);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new GatewayController(
                                new GatewayRouteTable(properties),
                                forwarder,
                                authenticationService,
                                (ignoredRoute, ignoredRequest, ignoredSubject) -> {}))
                .addFilters(new CorrelationIdFilter())
                .build();
    }

    private static GatewayAuthenticationProperties configuredAuthenticationProperties() {
        GatewayAuthenticationProperties authenticationProperties = new GatewayAuthenticationProperties();
        authenticationProperties.setBaseUrl("https://identity.test");
        authenticationProperties.setWorkloadIdentity("gateway-service");
        authenticationProperties.setWorkloadToken("test-gateway-workload-token");
        return authenticationProperties;
    }
}
