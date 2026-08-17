package com.lifeos.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;

import com.lifeos.gateway.config.GatewayProperties;
import com.lifeos.gateway.routing.GatewayRoute;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class RedisGatewayRateLimiterTest {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8-alpine")
            .withExposedPorts(6379);

    private static final GatewayRoute ROUTE = new GatewayRoute(
            "goals", "/api/v1/goals", URI.create("https://task-goal.test"), true, Set.of());

    @Test
    void enforcesTheAtomicFixedWindowAcrossConcurrentRequestsAndResetsAfterExpiry() throws Exception {
        GatewayProperties properties = properties(5);
        properties.getRateLimit().setWindow(Duration.ofSeconds(2));
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        ExecutorService executor = Executors.newFixedThreadPool(12);
        CountDownLatch ready = new CountDownLatch(12);
        CountDownLatch start = new CountDownLatch(1);
        try {
            RedisGatewayRateLimiter limiter = new RedisGatewayRateLimiter(
                    redis, properties, new GatewayRateLimitMetrics(new SimpleMeterRegistry()));
            List<Future<Boolean>> outcomes = new ArrayList<>();
            for (int index = 0; index < 12; index++) {
                outcomes.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        limiter.check(ROUTE, request("198.51.100.42"), null);
                        return true;
                    } catch (GatewayRateLimitExceededException exception) {
                        return false;
                    }
                }));
            }

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            long allowed = 0;
            for (Future<Boolean> outcome : outcomes) {
                if (outcome.get(5, TimeUnit.SECONDS)) {
                    allowed++;
                }
            }
            assertThat(allowed).isEqualTo(5);

            awaitWindowReset(limiter);
            for (int index = 1; index < 5; index++) {
                limiter.check(ROUTE, request("198.51.100.42"), null);
            }
            assertThatThrownBy(() -> limiter.check(ROUTE, request("198.51.100.42"), null))
                    .isInstanceOf(GatewayRateLimitExceededException.class);
        } finally {
            executor.shutdownNow();
            connectionFactory.destroy();
        }
    }

    private static void awaitWindowReset(RedisGatewayRateLimiter limiter) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            try {
                limiter.check(ROUTE, request("198.51.100.42"), null);
                return;
            } catch (GatewayRateLimitExceededException exception) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
            }
        }
        throw new AssertionError("Redis rate-limit window did not reset within 5 seconds");
    }

    private static GatewayProperties properties(int maxRequests) {
        GatewayProperties properties = new GatewayProperties();
        properties.getRateLimit().setMaxRequests(maxRequests);
        properties.getRateLimit().setPreAuthenticationMaxRequests(maxRequests);
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
