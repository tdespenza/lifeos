package com.lifeos.identity.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis-backed OIDC state store with atomic get-and-delete semantics.
 */
@Component
public class RedisOidcStateStore implements OidcStateStore {

    private static final Logger log = LoggerFactory.getLogger(RedisOidcStateStore.class);
    private static final String KEY_PREFIX = "lifeos:identity:oidc-state:";
    private static final Pattern STATE_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final DefaultRedisScript<String> CONSUME_SCRIPT = new DefaultRedisScript<>(
            "local value = redis.call('GET', KEYS[1]); "
                    + "if value then redis.call('DEL', KEYS[1]); end; return value;",
            String.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Creates the callback-state store.
     *
     * @param redisTemplate Redis template
     * @param objectMapper JSON serializer
     */
    public RedisOidcStateStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(String state, OidcAuthorizationState authorizationState, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(key(state), objectMapper.writeValueAsString(authorizationState), ttl);
        } catch (JsonProcessingException | RuntimeException exception) {
            log.atWarn().addKeyValue("event", "oidc_state_store_unavailable")
                    .log("OIDC callback state could not be stored");
            throw new AuthenticationDependencyUnavailableException(exception);
        }
    }

    @Override
    public Optional<OidcAuthorizationState> consume(String state) {
        if (state == null || !STATE_PATTERN.matcher(state).matches()) {
            return Optional.empty();
        }
        String payload;
        try {
            payload = redisTemplate.execute(CONSUME_SCRIPT, List.of(key(state)));
        } catch (RuntimeException exception) {
            log.atWarn().addKeyValue("event", "oidc_state_store_unavailable")
                    .log("OIDC callback state could not be consumed");
            throw new AuthenticationDependencyUnavailableException(exception);
        }
        if (payload == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(payload, OidcAuthorizationState.class));
        } catch (JsonProcessingException exception) {
            log.atWarn().addKeyValue("event", "oidc_state_payload_invalid")
                    .log("OIDC callback state payload could not be read");
            return Optional.empty();
        }
    }

    private String key(String state) {
        return KEY_PREFIX + state;
    }
}
