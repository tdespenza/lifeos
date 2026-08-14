package com.lifeos.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifeos.gateway.auth.GatewayAuthenticationClient;
import com.lifeos.gateway.auth.GatewayAuthenticationMetrics;
import com.lifeos.gateway.auth.GatewayAuthenticationService;
import com.lifeos.gateway.config.GatewayAuthenticationProperties;
import com.lifeos.gateway.config.GatewayProperties;
import com.lifeos.gateway.observability.CorrelationIdFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

class GatewayControllerTest {

    private static final String UPSTREAM = "https://task-goal.test";
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
        RestClient.Builder builder = RestClient.builder();
        upstream = MockRestServiceServer.bindTo(builder).build();
        meterRegistry = new SimpleMeterRegistry();
        GatewayForwarder forwarder = new GatewayForwarder(builder.build(), properties, meterRegistry);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new GatewayController(new GatewayRouteTable(properties), forwarder))
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
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
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
    void mapsOversizedUpstreamResponsesToBadGateway() throws Exception {
        properties.setMaxResponseBodyBytes(4);
        upstream.expect(requestTo(UPSTREAM + "/api/v1/goals"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("12345", MediaType.TEXT_PLAIN));

        mockMvc.perform(get("/api/v1/goals"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("UPSTREAM_UNAVAILABLE"));

        upstream.verify();
    }

    @Test
    void mapsUpstreamTimeoutsAndOtherTransportFailures() throws Exception {
        upstream.expect(requestTo(UPSTREAM + "/api/v1/goals"))
                .andRespond(withException(new SocketTimeoutException("timed out")));

        mockMvc.perform(get("/api/v1/goals"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("UPSTREAM_TIMEOUT"));
        upstream.verify();

        upstream.reset();
        upstream.expect(requestTo(UPSTREAM + "/api/v1/goals"))
                .andRespond(withException(new IOException("connection closed")));

        mockMvc.perform(get("/api/v1/goals"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("UPSTREAM_UNAVAILABLE"));
        upstream.verify();
    }

    @Test
    void mapsUpstreamServerErrorsWithoutRewritingTheirResponse() throws Exception {
        upstream.expect(requestTo(UPSTREAM + "/api/v1/goals"))
                .andRespond(withServerError());

        mockMvc.perform(get("/api/v1/goals"))
                .andExpect(status().isInternalServerError());

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

    private void useProtectedRoute() {
        properties.setRoutes(List.of(
                new GatewayProperties.Route("goals", "/api/v1/goals", UPSTREAM, true)));
        GatewayAuthenticationProperties authenticationProperties = new GatewayAuthenticationProperties();
        authenticationProperties.setBaseUrl("https://identity.test");
        authenticationProperties.setWorkloadIdentity("gateway-service");
        authenticationProperties.setWorkloadToken("test-gateway-workload-token");

        RestClient.Builder identityBuilder = RestClient.builder()
                .baseUrl(authenticationProperties.getBaseUrl());
        identity = MockRestServiceServer.bindTo(identityBuilder).build();
        GatewayAuthenticationClient authenticationClient = new GatewayAuthenticationClient(
                identityBuilder.build(), authenticationProperties);
        GatewayAuthenticationService authenticationService = new GatewayAuthenticationService(
                authenticationClient, new GatewayAuthenticationMetrics(meterRegistry));

        RestClient.Builder upstreamBuilder = RestClient.builder();
        upstream = MockRestServiceServer.bindTo(upstreamBuilder).build();
        GatewayForwarder forwarder = new GatewayForwarder(upstreamBuilder.build(), properties, meterRegistry);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new GatewayController(
                                new GatewayRouteTable(properties), forwarder, authenticationService))
                .addFilters(new CorrelationIdFilter())
                .build();
    }
}
