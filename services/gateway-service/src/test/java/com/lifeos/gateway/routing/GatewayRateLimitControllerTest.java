package com.lifeos.gateway.routing;

import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lifeos.gateway.auth.GatewayAuthenticationService;
import com.lifeos.gateway.auth.GatewayAuthenticatedSubject;
import com.lifeos.gateway.config.GatewayProperties;
import com.lifeos.gateway.observability.CorrelationIdFilter;
import com.lifeos.gateway.ratelimit.GatewayRateLimitDependencyUnavailableException;
import com.lifeos.gateway.ratelimit.GatewayRateLimitExceededException;
import com.lifeos.gateway.ratelimit.GatewayRateLimiter;
import java.util.List;
import java.util.UUID;
import org.mockito.InOrder;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class GatewayRateLimitControllerTest {

    @Test
    void returns429WithSafeRetryGuidanceBeforeForwardingToTheUpstream() throws Exception {
        GatewayProperties properties = new GatewayProperties();
        properties.setRoutes(List.of(new GatewayProperties.Route(
                "public", "/api/v1/public", "https://public.test", false)));
        GatewayForwarder forwarder = mock(GatewayForwarder.class);
        GatewayRateLimiter limiter = (route, request, subject) ->
                { throw new GatewayRateLimitExceededException(10, 60); };
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new GatewayController(
                        new GatewayRouteTable(properties),
                        forwarder,
                        mock(GatewayAuthenticationService.class),
                        limiter))
                .addFilters(new CorrelationIdFilter())
                .build();

        mockMvc.perform(get("/api/v1/public/resource"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "60"))
                .andExpect(header().string("RateLimit-Limit", "10"))
                .andExpect(header().string("RateLimit-Remaining", "0"))
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));

        verifyNoInteractions(forwarder);
    }

    @Test
    void returns503WhenRateLimitDependencyIsUnavailableBeforeForwardingToTheUpstream() throws Exception {
        GatewayProperties properties = new GatewayProperties();
        properties.setRoutes(List.of(new GatewayProperties.Route(
                "public", "/api/v1/public", "https://public.test", false)));
        GatewayForwarder forwarder = mock(GatewayForwarder.class);
        GatewayRateLimiter limiter = (route, request, subject) -> {
            throw new GatewayRateLimitDependencyUnavailableException();
        };
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new GatewayController(
                        new GatewayRouteTable(properties),
                        forwarder,
                        mock(GatewayAuthenticationService.class),
                        limiter))
                .addFilters(new CorrelationIdFilter())
                .build();

        mockMvc.perform(get("/api/v1/public/resource"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "5"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITER_UNAVAILABLE"));

        verifyNoInteractions(forwarder);
    }

    @Test
    void chargesProtectedRequestsBeforeAndAfterIdentityValidation() throws Exception {
        GatewayProperties properties = new GatewayProperties();
        properties.setRoutes(List.of(new GatewayProperties.Route(
                "protected", "/api/v1/protected", "https://protected.test", true)));
        GatewayForwarder forwarder = mock(GatewayForwarder.class);
        GatewayAuthenticationService authentication = mock(GatewayAuthenticationService.class);
        GatewayRateLimiter limiter = mock(GatewayRateLimiter.class);
        GatewayAuthenticatedSubject subject = new GatewayAuthenticatedSubject(
                UUID.randomUUID(), UUID.randomUUID(), "PASSWORD");
        when(authentication.authenticate(any(), eq("Bearer valid-token"))).thenReturn(subject);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new GatewayController(
                        new GatewayRouteTable(properties), forwarder, authentication, limiter))
                .addFilters(new CorrelationIdFilter())
                .build();

        mockMvc.perform(get("/api/v1/protected/resource")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer valid-token"))
                .andExpect(status().isOk());

        InOrder order = inOrder(limiter, authentication);
        order.verify(limiter).check(any(), any(), isNull());
        order.verify(authentication).authenticate(any(), eq("Bearer valid-token"));
        order.verify(limiter).check(any(), any(), eq(subject));
        verify(forwarder).forward(any(), any(), any(), any(), eq(subject));
    }
}
