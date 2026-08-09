package com.lifeos.identity.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis-backed login limiter using one atomic increment-and-expire script.
 *
 * <p>The limiter fails closed. A Redis timeout or script failure does not fall back to local JVM
 * state because local counters would diverge when the service is horizontally scaled.
 */
@Component
public class RedisLoginRateLimiter implements LoginRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisLoginRateLimiter.class);
    private static final String KEY_PREFIX = "lifeos:identity:login-attempts:";
    private static final DefaultRedisScript<Long> INCREMENT_SCRIPT = new DefaultRedisScript<>(
            "local count = redis.call('INCR', KEYS[1]); "
                    + "if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]); end; "
                    + "return count;",
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final IdentityAuthProperties properties;

    /**
     * Creates the Redis login limiter.
     *
     * @param redisTemplate Spring Data Redis template
     * @param properties authentication limits
     */
    public RedisLoginRateLimiter(StringRedisTemplate redisTemplate, IdentityAuthProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    /**
     * Atomically increments the attempt counter and rejects requests over the configured limit.
     *
     * @param normalizedEmail canonical email used to derive the key
     * @param clientAddress request source address used to derive the key
     */
    @Override
    public void check(String normalizedEmail, String clientAddress) {
        Duration window = properties.getRateLimit().getWindow();
        long windowSeconds = Math.max(1, window.toSeconds());
        String key = KEY_PREFIX + digest(normalizedEmail + "|" + clientAddress);
        try {
            Long count = redisTemplate.execute(
                    INCREMENT_SCRIPT,
                    List.of(key),
                    Long.toString(windowSeconds));
            if (count == null) {
                throw new AuthenticationDependencyUnavailableException();
            }
            if (count > properties.getRateLimit().getMaxAttempts()) {
                throw new LoginRateLimitExceededException(windowSeconds);
            }
        } catch (LoginRateLimitExceededException | AuthenticationDependencyUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            log.atWarn()
                    .addKeyValue("event", "login_rate_limiter_unavailable")
                    .log("Login rate limiter failed closed", exception);
            throw new AuthenticationDependencyUnavailableException(exception);
        }
    }

    /**
     * Derives a stable non-reversible limiter key without storing raw email or address data.
     *
     * @param value key material
     * @return lower-case SHA-256 digest
     */
    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the runtime", exception);
        }
    }
}
