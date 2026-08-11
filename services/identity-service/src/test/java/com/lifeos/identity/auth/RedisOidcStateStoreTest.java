package com.lifeos.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Verifies bounded and fail-closed Redis callback-state consumption.
 */
class RedisOidcStateStoreTest {

    @Test
    void rejectsMalformedStateBeforeCallingRedis() {
        StringRedisTemplate redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
        ObjectMapper objectMapper = org.mockito.Mockito.mock(ObjectMapper.class);
        RedisOidcStateStore store = new RedisOidcStateStore(redisTemplate, objectMapper);

        assertThat(store.consume("too-short")).isEqualTo(Optional.empty());

        verifyNoInteractions(redisTemplate, objectMapper);
    }

    @Test
    void treatsUnreadablePayloadAsMissingState() throws Exception {
        StringRedisTemplate redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
        ObjectMapper objectMapper = org.mockito.Mockito.mock(ObjectMapper.class);
        doReturn("not-json").when(redisTemplate).execute(any(DefaultRedisScript.class), anyList());
        org.mockito.Mockito.when(objectMapper.readValue("not-json", OidcAuthorizationState.class))
                .thenThrow(new JsonProcessingException("invalid payload") { });
        RedisOidcStateStore store = new RedisOidcStateStore(redisTemplate, objectMapper);

        assertThat(store.consume("a".repeat(43))).isEqualTo(Optional.empty());
    }
}
