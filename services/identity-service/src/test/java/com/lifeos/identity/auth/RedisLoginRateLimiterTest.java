package com.lifeos.identity.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Unit tests for Redis limiter decisions without requiring a running Redis process.
 */
class RedisLoginRateLimiterTest {

    @Test
    void rejectsAttemptWhenAtomicCounterExceedsConfiguredLimit() {
        StringRedisTemplate template = org.mockito.Mockito.mock(StringRedisTemplate.class);
        IdentityAuthProperties properties = new IdentityAuthProperties();
        properties.getRateLimit().setMaxAttempts(5);
        properties.getRateLimit().setWindow(java.time.Duration.ofSeconds(60));
        doReturn(6L).when(template).execute(any(DefaultRedisScript.class), anyList(), anyString());

        RedisLoginRateLimiter limiter = new RedisLoginRateLimiter(template, properties);

        assertThatThrownBy(() -> limiter.check("ada@example.com", "127.0.0.1"))
                .isInstanceOf(LoginRateLimitExceededException.class)
                .hasMessage("Authentication attempts are temporarily limited.");
    }

    @Test
    void failsClosedWhenRedisReturnsNoDecision() {
        StringRedisTemplate template = org.mockito.Mockito.mock(StringRedisTemplate.class);
        IdentityAuthProperties properties = new IdentityAuthProperties();
        doReturn(null).when(template).execute(any(DefaultRedisScript.class), anyList(), anyString());

        RedisLoginRateLimiter limiter = new RedisLoginRateLimiter(template, properties);

        assertThatThrownBy(() -> limiter.check("ada@example.com", "127.0.0.1"))
                .isInstanceOf(AuthenticationDependencyUnavailableException.class)
                .hasMessage("Authentication is temporarily unavailable.");
    }

    @Test
    void failsClosedWhenRedisThrows() {
        StringRedisTemplate template = org.mockito.Mockito.mock(StringRedisTemplate.class);
        IdentityAuthProperties properties = new IdentityAuthProperties();
        doReturn(null).when(template).execute(any(DefaultRedisScript.class), anyList(), anyString());
        org.mockito.Mockito.doThrow(new IllegalStateException("redis down"))
                .when(template).execute(any(DefaultRedisScript.class), anyList(), anyString());

        RedisLoginRateLimiter limiter = new RedisLoginRateLimiter(template, properties);

        assertThatThrownBy(() -> limiter.check("ada@example.com", "127.0.0.1"))
                .isInstanceOf(AuthenticationDependencyUnavailableException.class);
    }
}
