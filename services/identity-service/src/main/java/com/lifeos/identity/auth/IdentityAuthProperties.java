package com.lifeos.identity.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Externalized authentication limits and cryptographic configuration.
 *
 * <p>Production deployments must supply the JWT signing secret through a secrets manager-backed
 * environment or configuration source. No usable production secret is committed to the project.
 */
@ConfigurationProperties(prefix = "identity.auth")
@Validated
public class IdentityAuthProperties {

    @Valid
    private final RateLimit rateLimit = new RateLimit();
    @Valid
    private final Password password = new Password();
    @Valid
    private final Jwt jwt = new Jwt();
    @Valid
    private final Fingerprint fingerprint = new Fingerprint();
    @NotNull(message = "accessTokenTtl must be configured")
    private Duration accessTokenTtl = Duration.ofMinutes(5);
    @Min(value = 1, message = "maxSessionsPerAccount must be positive")
    private int maxSessionsPerAccount = 10;

    /**
     * Creates authentication properties with safe development defaults.
     */
    public IdentityAuthProperties() {
    }

    /**
     * Returns distributed login rate-limit settings.
     *
     * @return rate-limit settings
     */
    public RateLimit getRateLimit() {
        return rateLimit;
    }

    /**
     * Returns password hashing settings.
     *
     * @return password settings
     */
    public Password getPassword() {
        return password;
    }

    /**
     * Returns JWT signing settings.
     *
     * @return JWT settings
     */
    public Jwt getJwt() {
        return jwt;
    }

    /**
     * Returns keyed fingerprint secrets used for redacted security data.
     *
     * @return fingerprint secrets
     */
    public Fingerprint getFingerprint() {
        return fingerprint;
    }

    /**
     * Returns access-token lifetime.
     *
     * @return access-token TTL
     */
    public Duration getAccessTokenTtl() {
        return accessTokenTtl;
    }

    /**
     * Sets access-token lifetime from configuration binding.
     *
     * @param accessTokenTtl access-token TTL
     */
    public void setAccessTokenTtl(Duration accessTokenTtl) {
        if (accessTokenTtl == null || accessTokenTtl.isZero() || accessTokenTtl.isNegative()) {
            throw new IllegalArgumentException("accessTokenTtl must be positive");
        }
        this.accessTokenTtl = accessTokenTtl;
    }

    /**
     * Returns the maximum number of active sessions allowed for one account.
     *
     * @return active-session limit
     */
    public int getMaxSessionsPerAccount() {
        return maxSessionsPerAccount;
    }

    /**
     * Sets the maximum number of active sessions allowed for one account.
     *
     * @param maxSessionsPerAccount active-session limit
     */
    public void setMaxSessionsPerAccount(int maxSessionsPerAccount) {
        if (maxSessionsPerAccount < 1) {
            throw new IllegalArgumentException("maxSessionsPerAccount must be positive");
        }
        this.maxSessionsPerAccount = maxSessionsPerAccount;
    }

    /**
     * Confirms that the access-token lifetime is strictly positive during property validation.
     *
     * @return {@code true} when issued tokens have a usable lifetime
     */
    @AssertTrue(message = "accessTokenTtl must be positive")
    public boolean isAccessTokenTtlPositive() {
        return accessTokenTtl != null && !accessTokenTtl.isZero() && !accessTokenTtl.isNegative();
    }

    /**
     * Login rate-limit properties.
     */
    public static class RateLimit {

        @Min(value = 1, message = "maxAttempts must be positive")
        private int maxAttempts = 5;
        @NotNull(message = "window must be configured")
        private Duration window = Duration.ofMinutes(1);

        /**
         * Creates rate-limit properties with the default bounded window.
         */
        public RateLimit() {
        }

        /**
         * Returns the permitted attempts in one window.
         *
         * @return maximum attempts
         */
        public int getMaxAttempts() {
            return maxAttempts;
        }

        /**
         * Sets the permitted attempts in one window.
         *
         * @param maxAttempts maximum attempts
         */
        public void setMaxAttempts(int maxAttempts) {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("maxAttempts must be positive");
            }
            this.maxAttempts = maxAttempts;
        }

        /**
         * Returns the limiter window.
         *
         * @return window duration
         */
        public Duration getWindow() {
            return window;
        }

        /**
         * Sets the limiter window.
         *
         * @param window window duration
         */
        public void setWindow(Duration window) {
            if (window == null || window.isZero() || window.isNegative()) {
                throw new IllegalArgumentException("window must be positive");
            }
            this.window = window;
        }

        /**
         * Confirms that the configured window is strictly positive during property validation.
         *
         * @return {@code true} when the window can bound attempts
         */
        @AssertTrue(message = "window must be positive")
        public boolean isWindowPositive() {
            return window != null && !window.isZero() && !window.isNegative();
        }
    }

    /**
     * Secret-manager-backed keys for domain-separated HMAC fingerprints.
     */
    public static class Fingerprint {

        @NotBlank(message = "auditClientFingerprintSecret must be configured")
        private String auditClientFingerprintSecret;
        @NotBlank(message = "rateLimitKeySecret must be configured")
        private String rateLimitKeySecret;

        /**
         * Creates empty fingerprint settings so deployment configuration must provide both keys.
         */
        public Fingerprint() {
        }

        /**
         * Returns the dedicated audit fingerprint key.
         *
         * @return audit fingerprint key
         */
        public String getAuditClientFingerprintSecret() {
            return auditClientFingerprintSecret;
        }

        /**
         * Sets the dedicated audit fingerprint key from external configuration.
         *
         * @param auditClientFingerprintSecret audit fingerprint key
         */
        public void setAuditClientFingerprintSecret(String auditClientFingerprintSecret) {
            this.auditClientFingerprintSecret = auditClientFingerprintSecret;
        }

        /**
         * Returns the dedicated Redis limiter key secret.
         *
         * @return Redis limiter key secret
         */
        public String getRateLimitKeySecret() {
            return rateLimitKeySecret;
        }

        /**
         * Sets the dedicated Redis limiter key secret from external configuration.
         *
         * @param rateLimitKeySecret Redis limiter key secret
         */
        public void setRateLimitKeySecret(String rateLimitKeySecret) {
            this.rateLimitKeySecret = rateLimitKeySecret;
        }
    }

    /**
     * Argon2id password-hashing properties.
     */
    public static class Password {

        @Positive(message = "memoryKiB must be positive")
        private int memoryKiB = 19_456;
        @Positive(message = "iterations must be positive")
        private int iterations = 2;
        @Positive(message = "parallelism must be positive")
        private int parallelism = 1;
        @Positive(message = "maxConcurrentVerifications must be positive")
        private int maxConcurrentVerifications = 16;
        @NotNull(message = "verificationAcquireTimeout must be configured")
        private Duration verificationAcquireTimeout = Duration.ofMillis(250);

        /**
         * Creates password properties with the configured Argon2id defaults.
         */
        public Password() {
        }

        /**
         * Returns Argon2id memory cost in KiB.
         *
         * @return memory cost
         */
        public int getMemoryKiB() {
            return memoryKiB;
        }

        /**
         * Sets Argon2id memory cost in KiB.
         *
         * @param memoryKiB memory cost
         */
        public void setMemoryKiB(int memoryKiB) {
            if (memoryKiB < 1) {
                throw new IllegalArgumentException("memoryKiB must be positive");
            }
            this.memoryKiB = memoryKiB;
        }

        /**
         * Returns Argon2id iteration count.
         *
         * @return iteration count
         */
        public int getIterations() {
            return iterations;
        }

        /**
         * Sets Argon2id iteration count.
         *
         * @param iterations iteration count
         */
        public void setIterations(int iterations) {
            if (iterations < 1) {
                throw new IllegalArgumentException("iterations must be positive");
            }
            this.iterations = iterations;
        }

        /**
         * Returns Argon2id parallelism.
         *
         * @return parallelism
         */
        public int getParallelism() {
            return parallelism;
        }

        /**
         * Sets Argon2id parallelism.
         *
         * @param parallelism parallelism
         */
        public void setParallelism(int parallelism) {
            if (parallelism < 1) {
                throw new IllegalArgumentException("parallelism must be positive");
            }
            this.parallelism = parallelism;
        }

        /**
         * Returns the maximum number of concurrent Argon2id verifications per service instance.
         *
         * @return concurrent verification limit
         */
        public int getMaxConcurrentVerifications() {
            return maxConcurrentVerifications;
        }

        /**
         * Sets the maximum number of concurrent Argon2id verifications per service instance.
         *
         * @param maxConcurrentVerifications concurrent verification limit
         */
        public void setMaxConcurrentVerifications(int maxConcurrentVerifications) {
            if (maxConcurrentVerifications < 1) {
                throw new IllegalArgumentException("maxConcurrentVerifications must be positive");
            }
            this.maxConcurrentVerifications = maxConcurrentVerifications;
        }

        /**
         * Returns the bounded wait for a local Argon2id verification permit.
         *
         * @return permit acquisition timeout
         */
        public Duration getVerificationAcquireTimeout() {
            return verificationAcquireTimeout;
        }

        /**
         * Sets the bounded wait for a local Argon2id verification permit.
         *
         * @param verificationAcquireTimeout permit acquisition timeout
         */
        public void setVerificationAcquireTimeout(Duration verificationAcquireTimeout) {
            if (verificationAcquireTimeout == null || verificationAcquireTimeout.isZero()
                    || verificationAcquireTimeout.isNegative()) {
                throw new IllegalArgumentException("verificationAcquireTimeout must be positive");
            }
            this.verificationAcquireTimeout = verificationAcquireTimeout;
        }

        /**
         * Confirms that the local hashing wait is strictly positive during property validation.
         *
         * @return {@code true} when permit acquisition is bounded
         */
        @AssertTrue(message = "verificationAcquireTimeout must be positive")
        public boolean isVerificationAcquireTimeoutPositive() {
            return verificationAcquireTimeout != null
                    && !verificationAcquireTimeout.isZero()
                    && !verificationAcquireTimeout.isNegative();
        }
    }

    /**
     * JWT signing properties.
     */
    public static class Jwt {

        @NotBlank(message = "issuer must be configured")
        private String issuer = "lifeos-identity";
        @NotBlank(message = "signingSecret must be configured")
        private String signingSecret;

        /**
         * Creates JWT properties without a signing secret.
         */
        public Jwt() {
        }

        /**
         * Returns the JWT issuer claim.
         *
         * @return issuer
         */
        public String getIssuer() {
            return issuer;
        }

        /**
         * Sets the JWT issuer claim.
         *
         * @param issuer issuer
         */
        public void setIssuer(String issuer) {
            if (issuer == null || issuer.isBlank()) {
                throw new IllegalArgumentException("issuer must not be blank");
            }
            this.issuer = issuer;
        }

        /**
         * Returns the configured HMAC signing secret.
         *
         * @return secret, or {@code null} when not configured
         */
        public String getSigningSecret() {
            return signingSecret;
        }

        /**
         * Sets the HMAC signing secret from external configuration.
         *
         * @param signingSecret secret value
         */
        public void setSigningSecret(String signingSecret) {
            if (signingSecret == null || signingSecret.isBlank()
                    || signingSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
                throw new IllegalArgumentException("signingSecret must contain at least 32 bytes");
            }
            this.signingSecret = signingSecret;
        }
    }
}
