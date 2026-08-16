package com.lifeos.gateway.ratelimit;

import com.lifeos.gateway.auth.GatewayAuthenticatedSubject;
import com.lifeos.gateway.config.GatewayProperties;
import com.lifeos.gateway.routing.GatewayRoute;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis-backed, atomic fixed-window gateway limiter.
 *
 * <p>The Lua script performs {@code INCR} and the first-request {@code PEXPIRE} as one Redis
 * operation. This keeps counters correct across horizontally scaled gateway instances. Redis
 * failures fail closed because a local fallback would make the configured limit meaningless.
 */
@Component
public class RedisGatewayRateLimiter implements GatewayRateLimiter {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisGatewayRateLimiter.class);
    private static final String KEY_PREFIX = "lifeos:gateway:rate-limit:";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final DefaultRedisScript<Long> INCREMENT_SCRIPT = new DefaultRedisScript<>(
            "local count = redis.call('INCR', KEYS[1]); "
                    + "if count == 1 or redis.call('PTTL', KEYS[1]) < 0 "
                    + "then redis.call('PEXPIRE', KEYS[1], ARGV[1]); end; "
                    + "return count;",
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final GatewayProperties.RateLimit properties;
    private final GatewayRateLimitMetrics metrics;
    private final byte[] keySecret;

    public RedisGatewayRateLimiter(
            StringRedisTemplate redisTemplate,
            GatewayProperties gatewayProperties,
            GatewayRateLimitMetrics metrics) {
        this.redisTemplate = redisTemplate;
        this.properties = gatewayProperties.getRateLimit();
        this.metrics = metrics;
        String configuredKeySecret = properties.getKeySecret();
        if (configuredKeySecret == null || configuredKeySecret.isBlank()) {
            throw new IllegalStateException(
                    "gateway.rate-limit.key-secret must be supplied by secret management");
        }
        this.keySecret = configuredKeySecret.getBytes(StandardCharsets.UTF_8);
        for (GatewayProperties.Route route : gatewayProperties.getRoutes()) {
            metrics.recordLimit(route.getId(), properties.getMaxRequests());
        }
    }

    /**
     * Charges the validated account when present and otherwise the immediate client address.
     * Route identity is always part of the key, preventing one public route from consuming another
     * route's budget.
     */
    @Override
    public void check(GatewayRoute route, HttpServletRequest request, GatewayAuthenticatedSubject subject) {
        long startNanos = System.nanoTime();
        String routeId = route == null ? "unknown" : route.id();
        metrics.recordLimit(routeId, properties.getMaxRequests());
        try {
            String key = KEY_PREFIX + digest(rateLimitMaterial(route, request, subject));
            long windowMillis = Math.max(1L, properties.getWindow().toMillis());
            Long count = redisTemplate.execute(
                    INCREMENT_SCRIPT, List.of(key), Long.toString(windowMillis));
            if (count == null) {
                throw new GatewayRateLimitDependencyUnavailableException();
            }
            if (count > properties.getMaxRequests()) {
                metrics.recordRejected(routeId);
                throw new GatewayRateLimitExceededException(
                        properties.getMaxRequests(), retryAfterSeconds(windowMillis));
            }
            metrics.recordAllowed(routeId);
        } catch (GatewayRateLimitExceededException exception) {
            throw exception;
        } catch (GatewayRateLimitDependencyUnavailableException exception) {
            metrics.recordUnavailable(routeId);
            throw exception;
        } catch (RuntimeException exception) {
            metrics.recordUnavailable(routeId);
            LOGGER.atWarn()
                    .addKeyValue("event", "gateway_rate_limiter_unavailable")
                    .addKeyValue("routeId", routeId)
                    .log("Gateway rate limiter failed closed");
            throw new GatewayRateLimitDependencyUnavailableException(exception);
        } finally {
            metrics.recordLatency(routeId, startNanos);
        }
    }

    private static String rateLimitMaterial(
            GatewayRoute route, HttpServletRequest request, GatewayAuthenticatedSubject subject) {
        String routeId = route == null ? "unknown" : route.id();
        if (subject != null) {
            return "gateway-rate-limit|route=" + routeId + "|user=" + subject.accountId();
        }
        String clientAddress = request == null ? null : request.getRemoteAddr();
        if (clientAddress == null || clientAddress.isBlank()) {
            clientAddress = "unknown";
        }
        return "gateway-rate-limit|route=" + routeId + "|anonymous=" + clientAddress;
    }

    private String digest(String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(keySecret, HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("gateway rate-limit key digest unavailable", exception);
        }
    }

    private static int retryAfterSeconds(long windowMillis) {
        return (int) Math.max(1L, (windowMillis + 999L) / 1000L);
    }
}
