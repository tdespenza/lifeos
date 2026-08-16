package com.lifeos.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

import com.lifeos.gateway.auth.GatewayAuthenticatedSubject;
import com.lifeos.gateway.config.GatewayProperties;
import com.lifeos.gateway.routing.GatewayRoute;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

class RedisGatewayRateLimiterTest {

    private static final GatewayRoute ROUTE = new GatewayRoute(
            "goals", "/api/v1/goals", URI.create("https://task-goal.test"), true, Set.of());

    @Test
    void rejectsWhenTheAtomicRedisCounterExceedsTheConfiguredRouteBudget() {
        StringRedisTemplate redis = org.mockito.Mockito.mock(StringRedisTemplate.class);
        GatewayProperties properties = properties(5);
        doReturn(6L).when(redis).execute(any(DefaultRedisScript.class), anyList(), anyString());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RedisGatewayRateLimiter limiter = new RedisGatewayRateLimiter(
                redis, properties, new GatewayRateLimitMetrics(registry));

        assertThatThrownBy(() -> limiter.check(ROUTE, request("127.0.0.1"), null))
                .isInstanceOf(GatewayRateLimitExceededException.class)
                .satisfies(error -> {
                    GatewayRateLimitExceededException exception = (GatewayRateLimitExceededException) error;
                    assertThat(exception.getLimit()).isEqualTo(5);
                    assertThat(exception.getRetryAfterSeconds()).isEqualTo(60);
                });
        assertThat(registry.get("gateway.rate.limit.rejections").tag("route", "goals").counter().count())
                .isEqualTo(1);
        assertThat(registry.get("gateway.rate.limit.latency").tag("route", "goals").timer().count())
                .isEqualTo(1);
    }

    @Test
    void failsClosedWhenRedisCannotReturnAnAtomicDecision() {
        StringRedisTemplate redis = org.mockito.Mockito.mock(StringRedisTemplate.class);
        GatewayProperties properties = properties(5);
        doReturn(null).when(redis).execute(any(DefaultRedisScript.class), anyList(), anyString());
        RedisGatewayRateLimiter limiter = new RedisGatewayRateLimiter(
                redis, properties, new GatewayRateLimitMetrics(new SimpleMeterRegistry()));

        assertThatThrownBy(() -> limiter.check(ROUTE, request("127.0.0.1"), null))
                .isInstanceOf(GatewayRateLimitDependencyUnavailableException.class);
    }

    @Test
    void doesNotStoreRawAnonymousAddressesOrAccountIdsInRedisKeys() {
        StringRedisTemplate redis = org.mockito.Mockito.mock(StringRedisTemplate.class);
        GatewayProperties properties = properties(5);
        doReturn(1L).when(redis).execute(any(DefaultRedisScript.class), anyList(), anyString());
        RedisGatewayRateLimiter limiter = new RedisGatewayRateLimiter(
                redis, properties, new GatewayRateLimitMetrics(new SimpleMeterRegistry()));
        GatewayAuthenticatedSubject subject = new GatewayAuthenticatedSubject(
                UUID.randomUUID(), UUID.randomUUID(), "PASSWORD");

        limiter.check(ROUTE, request("203.0.113.8"), subject);

        org.mockito.ArgumentCaptor<List<String>> keys = org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(redis).execute(any(DefaultRedisScript.class), keys.capture(), anyString());
        assertThat(keys.getValue()).hasSize(1);
        assertThat(keys.getValue().getFirst())
                .startsWith("lifeos:gateway:rate-limit:")
                .doesNotContain("203.0.113.8")
                .doesNotContain(subject.accountId().toString());
    }

    @Test
    void failsClosedWhenRedisThrows() {
        StringRedisTemplate redis = org.mockito.Mockito.mock(StringRedisTemplate.class);
        GatewayProperties properties = properties(5);
        doThrow(new IllegalStateException("redis down"))
                .when(redis).execute(any(DefaultRedisScript.class), anyList(), anyString());
        RedisGatewayRateLimiter limiter = new RedisGatewayRateLimiter(
                redis, properties, new GatewayRateLimitMetrics(new SimpleMeterRegistry()));

        assertThatThrownBy(() -> limiter.check(ROUTE, request("127.0.0.1"), null))
                .isInstanceOf(GatewayRateLimitDependencyUnavailableException.class);
    }

    private static GatewayProperties properties(int maxRequests) {
        GatewayProperties properties = new GatewayProperties();
        properties.getRateLimit().setMaxRequests(maxRequests);
        properties.getRateLimit().setWindow(Duration.ofSeconds(60));
        properties.getRateLimit().setKeySecret("test-only-rate-limit-secret");
        return properties;
    }

    private static HttpServletRequest request(String remoteAddress) {
        HttpServletRequest request = org.mockito.Mockito.mock(HttpServletRequest.class);
        doReturn(remoteAddress).when(request).getRemoteAddr();
        return request;
    }
}
