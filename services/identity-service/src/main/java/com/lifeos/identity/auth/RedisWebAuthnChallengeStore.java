package com.lifeos.identity.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.yubico.webauthn.AssertionRequest;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Redis-backed WebAuthn assertion-request store with atomic get-and-delete semantics.
 */
@Component
public class RedisWebAuthnChallengeStore implements WebAuthnChallengeStore {

    private static final Logger log = LoggerFactory.getLogger(RedisWebAuthnChallengeStore.class);
    private static final String KEY_PREFIX = "lifeos:identity:webauthn-challenge:";
    private static final Pattern CHALLENGE_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final DefaultRedisScript<String> CONSUME_SCRIPT = new DefaultRedisScript<>(
            "local value = redis.call('GET', KEYS[1]); "
                    + "if not value then return nil end; "
                    + "redis.call('DEL', KEYS[1]); return value;",
            String.class);

    private final StringRedisTemplate redisTemplate;

    /**
     * Creates the Redis challenge store.
     *
     * @param redisTemplate Redis string template
     */
    public RedisWebAuthnChallengeStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void save(String challengeId, AssertionRequest request, Duration ttl) {
        if (!validChallengeId(challengeId) || request == null || ttl == null
                || ttl.isZero() || ttl.isNegative()) {
            throw new AuthenticationDependencyUnavailableException();
        }
        try {
            redisTemplate.opsForValue().set(key(challengeId), request.toJson(), ttl);
        } catch (JsonProcessingException | RuntimeException exception) {
            log.atWarn().addKeyValue("event", "webauthn_challenge_store_unavailable")
                    .log("WebAuthn challenge could not be stored");
            throw new AuthenticationDependencyUnavailableException(exception);
        }
    }

    @Override
    public Optional<AssertionRequest> consume(String challengeId) {
        if (!validChallengeId(challengeId)) {
            return Optional.empty();
        }
        String payload;
        try {
            payload = redisTemplate.execute(CONSUME_SCRIPT, List.of(key(challengeId)));
        } catch (RuntimeException exception) {
            log.atWarn().addKeyValue("event", "webauthn_challenge_store_unavailable")
                    .log("WebAuthn challenge could not be consumed");
            throw new AuthenticationDependencyUnavailableException(exception);
        }
        if (payload == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(AssertionRequest.fromJson(payload));
        } catch (Exception exception) {
            log.atWarn().addKeyValue("event", "webauthn_challenge_payload_invalid")
                    .log("WebAuthn challenge payload could not be read");
            return Optional.empty();
        }
    }

    private boolean validChallengeId(String challengeId) {
        return challengeId != null && CHALLENGE_ID_PATTERN.matcher(challengeId).matches();
    }

    private String key(String challengeId) {
        return KEY_PREFIX + challengeId;
    }
}
