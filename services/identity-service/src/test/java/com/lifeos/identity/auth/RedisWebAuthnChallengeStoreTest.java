package com.lifeos.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.yubico.webauthn.AssertionRequest;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredentialRequestOptions;
import com.yubico.webauthn.data.UserVerificationRequirement;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * Verifies bounded and atomic Redis challenge behavior.
 */
class RedisWebAuthnChallengeStoreTest {

    private static final WebAuthnChallengeId CHALLENGE_ID =
            new WebAuthnChallengeId("c".repeat(43));

    @Test
    void rejectsMalformedChallengeBeforeCallingRedis() {
        StringRedisTemplate redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
        RedisWebAuthnChallengeStore store = new RedisWebAuthnChallengeStore(redisTemplate);

        assertThat(WebAuthnChallengeId.parse("too-short")).isEmpty();
        assertThat(store.consume(null)).isEqualTo(Optional.empty());

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void consumesStoredPayloadAtomicallyAndParsesAssertionRequest() throws Exception {
        StringRedisTemplate redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
        AssertionRequest request = assertionRequest();
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = org.mockito.Mockito.mock(ValueOperations.class);
        doReturn(values).when(redisTemplate).opsForValue();
        doReturn(request.toJson()).when(values).getAndDelete(
                "lifeos:identity:webauthn-challenge:" + CHALLENGE_ID.value());
        RedisWebAuthnChallengeStore store = new RedisWebAuthnChallengeStore(redisTemplate);

        Optional<AssertionRequest> consumed = store.consume(CHALLENGE_ID);

        assertThat(consumed).contains(request);
        verify(values).getAndDelete("lifeos:identity:webauthn-challenge:" + CHALLENGE_ID.value());
    }

    @Test
    void storesExactRequestWithBoundedTtl() throws Exception {
        StringRedisTemplate redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = org.mockito.Mockito.mock(ValueOperations.class);
        doReturn(values).when(redisTemplate).opsForValue();
        RedisWebAuthnChallengeStore store = new RedisWebAuthnChallengeStore(redisTemplate);
        AssertionRequest request = assertionRequest();

        store.save(CHALLENGE_ID, request, Duration.ofMinutes(5));

        verify(values).set(
                eq("lifeos:identity:webauthn-challenge:" + CHALLENGE_ID.value()),
                eq(request.toJson()),
                eq(Duration.ofMinutes(5)));
    }

    private AssertionRequest assertionRequest() {
        return AssertionRequest.builder()
                .publicKeyCredentialRequestOptions(PublicKeyCredentialRequestOptions.builder()
                        .challenge(new ByteArray(new byte[] {1, 2, 3}))
                        .rpId("localhost")
                        .userVerification(UserVerificationRequirement.REQUIRED)
                        .build())
                .build();
    }
}
