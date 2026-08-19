package com.lifeos.identity.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Redis-backed single-use WebAuthn registration state. */
@Component
public class RedisWebAuthnRegistrationChallengeStore implements WebAuthnRegistrationChallengeStore {

    private static final String KEY_PREFIX = "lifeos:identity:webauthn-registration:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisWebAuthnRegistrationChallengeStore(
            StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(WebAuthnChallengeId id, WebAuthnRegistrationChallenge challenge, Duration ttl) {
        if (id == null || challenge == null || ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new AuthenticationDependencyUnavailableException();
        }
        try {
            String payload = objectMapper.createObjectNode()
                    .put("accountId", challenge.accountId().toString())
                    .put("request", challenge.request().toJson())
                    .toString();
            redisTemplate.opsForValue().set(KEY_PREFIX + id.value(), payload, ttl);
        } catch (JsonProcessingException | RuntimeException exception) {
            throw new AuthenticationDependencyUnavailableException(exception);
        }
    }

    @Override
    public Optional<WebAuthnRegistrationChallenge> consume(WebAuthnChallengeId id) {
        if (id == null) {
            return Optional.empty();
        }
        try {
            String payload = redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + id.value());
            if (payload == null) {
                return Optional.empty();
            }
            JsonNode root = objectMapper.readTree(payload);
            UUID accountId = UUID.fromString(root.path("accountId").asText());
            PublicKeyCredentialCreationOptions options = PublicKeyCredentialCreationOptions.fromJson(
                    root.path("request").asText());
            return Optional.of(new WebAuthnRegistrationChallenge(accountId, options));
        } catch (Exception exception) {
            return Optional.empty();
        }
    }
}
