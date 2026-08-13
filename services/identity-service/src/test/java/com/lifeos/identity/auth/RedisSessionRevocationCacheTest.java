package com.lifeos.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisSessionRevocationCacheTest {

    @Test
    void cacheMissIsReturnedWhenRedisCannotDecide() {
        StringRedisTemplate template = org.mockito.Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = org.mockito.Mockito.mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenThrow(new IllegalStateException("redis restarted"));

        Optional<Boolean> result = new RedisSessionRevocationCache(template).isRevoked(UUID.randomUUID());

        assertThat(result).isEmpty();
    }

    @Test
    void revokedMarkerUsesSessionExpiryAsItsUpperBound() {
        StringRedisTemplate template = org.mockito.Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = org.mockito.Mockito.mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(values);
        UUID sessionId = UUID.randomUUID();
        Instant expiry = Instant.now().plusSeconds(60);

        new RedisSessionRevocationCache(template).markRevoked(sessionId, expiry);

        verify(values).set(anyString(), eq("1"), any(Duration.class));
    }
}
