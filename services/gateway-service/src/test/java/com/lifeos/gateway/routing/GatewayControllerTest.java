package com.lifeos.gateway.routing;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifeos.gateway.config.GatewayProperties;
import com.lifeos.gateway.observability.CorrelationIdFilter;
import java.util.List;
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

    private MockRestServiceServer upstream;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        GatewayProperties properties = new GatewayProperties();
        properties.setRoutes(List.of(new GatewayProperties.Route("goals", "/api/v1/goals", UPSTREAM)));
        RestClient.Builder builder = RestClient.builder();
        upstream = MockRestServiceServer.bindTo(builder).build();
        GatewayForwarder forwarder = new GatewayForwarder(builder.build(), properties);
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
    void returnsControlledNotFoundWithoutForwardingUnknownRoutes() throws Exception {
        mockMvc.perform(get("/api/v1/internal/authorization/decisions"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("ROUTE_NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("The requested API route does not exist."));
    }
}
