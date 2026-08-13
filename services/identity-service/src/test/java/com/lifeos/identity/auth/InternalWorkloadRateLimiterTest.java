package com.lifeos.identity.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** Verifies the internal workload limiter is distinct from the credential-attempt budget. */
class InternalWorkloadRateLimiterTest {

    @Test
    void usesTheSeparateWorkloadBudgetRatherThanTheCredentialAttemptLimit() {
        StringRedisTemplate template = org.mockito.Mockito.mock(StringRedisTemplate.class);
        IdentityAuthProperties properties = propertiesWithLimit(10);
        properties.getRateLimit().setMaxAttempts(1);
        doReturn(2L).when(template).execute(any(DefaultRedisScript.class), anyList(), anyString());

        InternalWorkloadRateLimiter limiter = new InternalWorkloadRateLimiter(template, properties);

        assertThatCode(() -> limiter.check("task-goal-service")).doesNotThrowAnyException();
    }

    @Test
    void rejectsAfterTheSeparateWorkloadBudgetIsExhausted() {
        StringRedisTemplate template = org.mockito.Mockito.mock(StringRedisTemplate.class);
        IdentityAuthProperties properties = propertiesWithLimit(10);
        doReturn(11L).when(template).execute(any(DefaultRedisScript.class), anyList(), anyString());

        InternalWorkloadRateLimiter limiter = new InternalWorkloadRateLimiter(template, properties);

        assertThatThrownBy(() -> limiter.check("task-goal-service"))
                .isInstanceOf(InternalWorkloadRateLimitExceededException.class)
                .hasMessage("Internal authorization requests are temporarily limited.");
    }

    @Test
    void failsClosedWhenRedisCannotDecideTheWorkloadBudget() {
        StringRedisTemplate template = org.mockito.Mockito.mock(StringRedisTemplate.class);
        IdentityAuthProperties properties = propertiesWithLimit(10);
        doReturn(null).when(template).execute(any(DefaultRedisScript.class), anyList(), anyString());

        InternalWorkloadRateLimiter limiter = new InternalWorkloadRateLimiter(template, properties);

        assertThatThrownBy(() -> limiter.check("task-goal-service"))
                .isInstanceOf(AuthenticationDependencyUnavailableException.class);
    }

    private IdentityAuthProperties propertiesWithLimit(int maxRequests) {
        IdentityAuthProperties properties = new IdentityAuthProperties();
        properties.getFingerprint().setRateLimitKeySecret(
                "test-only-rate-limit-key-secret-that-is-at-least-32-bytes");
        properties.getAuthorization().getWorkloadRateLimit().setMaxRequests(maxRequests);
        properties.getAuthorization().getWorkloadRateLimit().setWindow(Duration.ofSeconds(60));
        return properties;
    }
}
