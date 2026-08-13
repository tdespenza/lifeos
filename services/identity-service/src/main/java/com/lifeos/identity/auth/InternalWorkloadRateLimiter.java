package com.lifeos.identity.auth;

import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis-backed rate limiter for authenticated internal workloads.
 *
 * <p>This is deliberately separate from the five-attempt credential limiter: protected-service
 * traffic is expected to perform a durable session check on every request. The counter is keyed
 * only by a keyed digest of the verified workload identity, never a bearer token, account, or
 * resource identifier. Redis failures reject the internal call rather than creating a local,
 * inconsistent fallback.
 */
@Component
public class InternalWorkloadRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(InternalWorkloadRateLimiter.class);
    private static final String KEY_PREFIX = "lifeos:identity:internal-workload-requests:";
    private static final DefaultRedisScript<Long> INCREMENT_SCRIPT = new DefaultRedisScript<>(
            "local count = redis.call('INCR', KEYS[1]); "
                    + "if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]); end; "
                    + "return count;",
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final IdentityAuthProperties.Authorization.WorkloadRateLimit rateLimit;
    private final HmacSha256Digest keyDigest;

    /**
     * Creates the fail-closed internal limiter.
     *
     * @param redisTemplate distributed counter store
     * @param properties identity settings containing independently configured limit and HMAC key
     */
    public InternalWorkloadRateLimiter(StringRedisTemplate redisTemplate, IdentityAuthProperties properties) {
        this.redisTemplate = redisTemplate;
        this.rateLimit = properties.getAuthorization().getWorkloadRateLimit();
        this.keyDigest = new HmacSha256Digest(
                properties.getFingerprint().getRateLimitKeySecret(),
                "IDENTITY_RATE_LIMIT_KEY_SECRET");
    }

    /**
     * Charges one request to an already authenticated workload.
     *
     * @param workloadIdentity verified workload identity, never a caller-controlled value
     * @throws InternalWorkloadRateLimitExceededException when the configured budget is exhausted
     * @throws AuthenticationDependencyUnavailableException when Redis cannot decide safely
     */
    public void check(String workloadIdentity) {
        Duration window = rateLimit.getWindow();
        long windowSeconds = Math.max(1, window.toSeconds());
        String key = KEY_PREFIX + keyDigest.digest("internal-workload|" + workloadIdentity);
        try {
            Long count = redisTemplate.execute(INCREMENT_SCRIPT, List.of(key), Long.toString(windowSeconds));
            if (count == null) {
                throw new AuthenticationDependencyUnavailableException();
            }
            if (count > rateLimit.getMaxRequests()) {
                throw new InternalWorkloadRateLimitExceededException(windowSeconds);
            }
        } catch (InternalWorkloadRateLimitExceededException | AuthenticationDependencyUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            log.atWarn()
                    .addKeyValue("event", "internal_workload_rate_limiter_unavailable")
                    .log("Internal workload rate limiter failed closed");
            throw new AuthenticationDependencyUnavailableException(exception);
        }
    }
}
