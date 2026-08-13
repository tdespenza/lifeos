package com.lifeos.identity.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Redis implementation of the revocation-only cache.
 *
 * <p>Only negative authorization state is cached. Redis errors are intentionally converted to a
 * cache miss so PostgreSQL remains the fail-closed authority.
 */
@Service
public class RedisSessionRevocationCache implements SessionRevocationCache {

    private static final Logger log = LoggerFactory.getLogger(RedisSessionRevocationCache.class);
    private static final String KEY_PREFIX = "lifeos:auth-session:revoked:";
    private static final String REVOKED = "1";

    private final StringRedisTemplate redisTemplate;

    /**
     * Creates the Redis-backed revocation cache.
     *
     * @param redisTemplate configured Redis client with bounded command timeout
     */
    public RedisSessionRevocationCache(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Optional<Boolean> isRevoked(UUID sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        try {
            String value = redisTemplate.opsForValue().get(key(sessionId));
            return value == null ? Optional.empty() : Optional.of(REVOKED.equals(value));
        } catch (RuntimeException exception) {
            log.atWarn()
                    .addKeyValue("event", "session_revocation_cache_read_unavailable")
                    .addKeyValue("dependencyException", exception.getClass().getName())
                    .log("Session revocation cache read failed; using durable authority");
            return Optional.empty();
        }
    }

    @Override
    public void markRevoked(UUID sessionId, Instant expiresAt) {
        if (sessionId == null || expiresAt == null) {
            return;
        }
        Duration ttl = Duration.between(Instant.now(), expiresAt);
        if (ttl.isZero() || ttl.isNegative()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(key(sessionId), REVOKED, ttl);
        } catch (RuntimeException exception) {
            // Durable revocation has already committed. A cache write failure is safe because the
            // next validation falls through to PostgreSQL.
            log.atWarn()
                    .addKeyValue("event", "session_revocation_cache_write_unavailable")
                    .addKeyValue("dependencyException", exception.getClass().getName())
                    .log("Session revocation cache write failed; durable authority remains active");
        }
    }

    private String key(UUID sessionId) {
        return KEY_PREFIX + sessionId;
    }
}
