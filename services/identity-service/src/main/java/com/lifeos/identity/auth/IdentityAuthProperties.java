package com.lifeos.identity.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalized authentication limits and cryptographic configuration.
 *
 * <p>Production deployments must supply the JWT signing secret through a secrets manager-backed
 * environment or configuration source. No usable production secret is committed to the project.
 */
@ConfigurationProperties(prefix = "identity.auth")
public class IdentityAuthProperties {

    private final RateLimit rateLimit = new RateLimit();
    private final Password password = new Password();
    private final Jwt jwt = new Jwt();
    private Duration accessTokenTtl = Duration.ofMinutes(5);
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
        this.maxSessionsPerAccount = maxSessionsPerAccount;
    }

    /**
     * Login rate-limit properties.
     */
    public static class RateLimit {

        private int maxAttempts = 5;
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
            this.window = window;
        }
    }

    /**
     * Argon2id password-hashing properties.
     */
    public static class Password {

        private int memoryKiB = 19_456;
        private int iterations = 2;
        private int parallelism = 1;
        private int maxConcurrentVerifications = 16;
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
            this.verificationAcquireTimeout = verificationAcquireTimeout;
        }
    }

    /**
     * JWT signing properties.
     */
    public static class Jwt {

        private String issuer = "lifeos-identity";
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
            this.signingSecret = signingSecret;
        }
    }
}
