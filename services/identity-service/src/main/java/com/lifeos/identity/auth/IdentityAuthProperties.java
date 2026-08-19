package com.lifeos.identity.auth;

import com.lifeos.identity.authorization.DefaultAuthorizationPolicyRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import com.yubico.webauthn.data.UserVerificationRequirement;
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
    private final Registration registration = new Registration();
    @Valid
    private final Jwt jwt = new Jwt();
    @Valid
    private final Fingerprint fingerprint = new Fingerprint();
    @Valid
    private final Oidc oidc = new Oidc();
    @Valid
    private final WebAuthn webauthn = new WebAuthn();
    @Valid
    private final Authorization authorization = new Authorization();
    private Set<String> trustedProxyAddresses = new LinkedHashSet<>();
    @NotNull(message = "accessTokenTtl must be configured")
    private Duration accessTokenTtl = Duration.ofMinutes(5);
    @Min(value = 1, message = "maxSessionsPerAccount must be positive")
    private int maxSessionsPerAccount = 10;
    @Min(value = 1, message = "defaultSessionPageSize must be positive")
    private int defaultSessionPageSize = 20;
    @Min(value = 1, message = "maxSessionPageSize must be positive")
    private int maxSessionPageSize = 100;

    /**
     * Creates authentication properties with safe development defaults.
     */
    public IdentityAuthProperties() {
    }

    /**
     * Returns the exact immediate proxy addresses allowed to supply an X-Forwarded-For header.
     *
     * @return trusted proxy addresses
     */
    public Set<String> getTrustedProxyAddresses() {
        return trustedProxyAddresses;
    }

    /**
     * Replaces the trusted proxy address allow-list during configuration binding.
     *
     * @param trustedProxyAddresses exact proxy addresses
     */
    public void setTrustedProxyAddresses(Set<String> trustedProxyAddresses) {
        this.trustedProxyAddresses = trustedProxyAddresses == null
                ? new LinkedHashSet<>()
                : new LinkedHashSet<>(trustedProxyAddresses);
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
     * Returns public-registration idempotency settings.
     *
     * @return registration settings
     */
    public Registration getRegistration() {
        return registration;
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
     * Returns OIDC provider and callback settings.
     *
     * @return OIDC settings
     */
    public Oidc getOidc() {
        return oidc;
    }

    /**
     * Returns WebAuthn relying-party and challenge settings.
     *
     * @return WebAuthn settings
     */
    public WebAuthn getWebauthn() {
        return webauthn;
    }

    /**
     * Returns the internal authorization-decision settings.
     *
     * <p>Workload credentials are intentionally externalized. An unconfigured workload identity
     * is rejected at the internal boundary rather than falling back to caller-controlled headers.
     *
     * @return authorization settings
     */
    public Authorization getAuthorization() {
        return authorization;
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
     * Returns the default session-list page size.
     *
     * @return default session page size
     */
    public int getDefaultSessionPageSize() {
        return defaultSessionPageSize;
    }

    /**
     * Sets the default session-list page size.
     *
     * @param defaultSessionPageSize page size
     */
    public void setDefaultSessionPageSize(int defaultSessionPageSize) {
        if (defaultSessionPageSize < 1) {
            throw new IllegalArgumentException("defaultSessionPageSize must be positive");
        }
        this.defaultSessionPageSize = defaultSessionPageSize;
    }

    /**
     * Returns the maximum accepted session-list page size.
     *
     * @return maximum session page size
     */
    public int getMaxSessionPageSize() {
        return maxSessionPageSize;
    }

    /**
     * Sets the maximum accepted session-list page size.
     *
     * @param maxSessionPageSize maximum page size
     */
    public void setMaxSessionPageSize(int maxSessionPageSize) {
        if (maxSessionPageSize < 1) {
            throw new IllegalArgumentException("maxSessionPageSize must be positive");
        }
        this.maxSessionPageSize = maxSessionPageSize;
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
     * Ensures the default page cannot exceed the configured hard response bound.
     *
     * @return {@code true} when page defaults are internally consistent
     */
    @AssertTrue(message = "defaultSessionPageSize must not exceed maxSessionPageSize")
    public boolean isSessionPageSizeConfigurationValid() {
        return defaultSessionPageSize > 0
                && maxSessionPageSize > 0
                && defaultSessionPageSize <= maxSessionPageSize;
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
     * Versioned internal authorization settings.
     *
     * <p>Each workload credential is supplied by deployment configuration or a secret manager.
     * The empty default deliberately authorizes no workload; this prevents an internal endpoint
     * from becoming public when a deployment omits a secret.
     */
    public static class Authorization {

        @NotBlank(message = "policyVersion must be configured")
        private String policyVersion = "v2";
        @Valid
        private final WorkloadRateLimit workloadRateLimit = new WorkloadRateLimit();
        private Map<String, String> workloadIdentities = new LinkedHashMap<>();

        /**
         * Creates settings with the current policy version and no trusted workloads.
         */
        public Authorization() {
        }

        /**
         * Returns the policy version a protected service must request.
         *
         * @return policy version
         */
        public String getPolicyVersion() {
            return policyVersion;
        }

        /**
         * Sets the active policy version from deployment configuration.
         *
         * @param policyVersion stable policy version
         */
        public void setPolicyVersion(String policyVersion) {
            if (policyVersion == null || policyVersion.isBlank() || policyVersion.length() > 64) {
                throw new IllegalArgumentException("policyVersion must be between 1 and 64 characters");
            }
            if (!DefaultAuthorizationPolicyRepository.isSupportedPolicyVersion(policyVersion)) {
                throw new IllegalArgumentException("policyVersion is not implemented by this authorization authority");
            }
            this.policyVersion = policyVersion;
        }

        /**
         * Returns the bounded per-workload rate limit used by internal validation and decision
         * adapters. It is intentionally independent of the five-attempt user login limiter.
         *
         * @return internal workload rate-limit settings
         */
        public WorkloadRateLimit getWorkloadRateLimit() {
            return workloadRateLimit;
        }

        /**
         * Returns a defensive copy of configured workload credential mappings.
         *
         * @return workload identity to secret mapping
         */
        public Map<String, String> getWorkloadIdentities() {
            return Map.copyOf(workloadIdentities);
        }

        /**
         * Replaces the configured workload credential mappings during configuration binding.
         *
         * <p>Values are credentials and must never be logged or returned from an endpoint.
         *
         * @param workloadIdentities workload identity to secret mapping
         */
        public void setWorkloadIdentities(Map<String, String> workloadIdentities) {
            this.workloadIdentities = workloadIdentities == null
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(workloadIdentities);
        }

        /**
         * Looks up one configured workload credential without exposing mappings to callers.
         *
         * @param workloadIdentity caller workload identity
         * @return configured credential, or {@code null} when the workload is not trusted
         */
        public String workloadCredential(String workloadIdentity) {
            return workloadIdentity == null ? null : workloadIdentities.get(workloadIdentity);
        }

        /** Rate-limit settings for authenticated internal workloads. */
        public static class WorkloadRateLimit {

            @Min(value = 1, message = "maxRequests must be positive")
            private int maxRequests = 60_000;
            @NotNull(message = "window must be configured")
            private Duration window = Duration.ofMinutes(1);

            /** Creates a rate limit sized for protected-service traffic, not login attempts. */
            public WorkloadRateLimit() {
            }

            /** @return maximum requests per configured window for one workload */
            public int getMaxRequests() {
                return maxRequests;
            }

            /** @param maxRequests maximum requests per configured window */
            public void setMaxRequests(int maxRequests) {
                if (maxRequests < 1) {
                    throw new IllegalArgumentException("maxRequests must be positive");
                }
                this.maxRequests = maxRequests;
            }

            /** @return bounded rate-limit window */
            public Duration getWindow() {
                return window;
            }

            /** @param window bounded rate-limit window */
            public void setWindow(Duration window) {
                if (window == null || window.isZero() || window.isNegative()) {
                    throw new IllegalArgumentException("window must be positive");
                }
                this.window = window;
            }

            /** @return whether the configured window is usable */
            @AssertTrue(message = "window must be positive")
            public boolean isWindowPositive() {
                return window != null && !window.isZero() && !window.isNegative();
            }
        }
    }

    /**
     * OIDC provider configuration. Providers are explicitly allow-listed by deployment
     * configuration; request input can never select an arbitrary issuer or redirect URI.
     */
    public static class Oidc {

        @NotNull(message = "callbackStateTtl must be configured")
        private Duration callbackStateTtl = Duration.ofMinutes(5);
        @NotNull(message = "providerConnectTimeout must be configured")
        private Duration providerConnectTimeout = Duration.ofSeconds(2);
        @NotNull(message = "providerReadTimeout must be configured")
        private Duration providerReadTimeout = Duration.ofSeconds(5);
        @Valid
        private Map<String, Provider> providers = new LinkedHashMap<>();

        /**
         * Creates OIDC settings with a bounded callback-state lifetime.
         */
        public Oidc() {
        }

        /**
         * Returns the callback-state TTL.
         *
         * @return callback-state TTL
         */
        public Duration getCallbackStateTtl() {
            return callbackStateTtl;
        }

        /**
         * Sets the callback-state TTL.
         *
         * @param callbackStateTtl callback-state TTL
         */
        public void setCallbackStateTtl(Duration callbackStateTtl) {
            if (callbackStateTtl == null || callbackStateTtl.isZero() || callbackStateTtl.isNegative()) {
                throw new IllegalArgumentException("callbackStateTtl must be positive");
            }
            this.callbackStateTtl = callbackStateTtl;
        }

        /**
         * Returns the bounded provider TCP connection timeout.
         *
         * @return connection timeout
         */
        public Duration getProviderConnectTimeout() {
            return providerConnectTimeout;
        }

        /**
         * Sets the provider TCP connection timeout.
         *
         * @param providerConnectTimeout connection timeout
         */
        public void setProviderConnectTimeout(Duration providerConnectTimeout) {
            this.providerConnectTimeout = positiveDuration(providerConnectTimeout, "providerConnectTimeout");
        }

        /**
         * Returns the bounded provider response timeout.
         *
         * @return response timeout
         */
        public Duration getProviderReadTimeout() {
            return providerReadTimeout;
        }

        /**
         * Sets the provider response timeout.
         *
         * @param providerReadTimeout response timeout
         */
        public void setProviderReadTimeout(Duration providerReadTimeout) {
            this.providerReadTimeout = positiveDuration(providerReadTimeout, "providerReadTimeout");
        }

        /**
         * Returns the explicitly configured providers.
         *
         * @return provider map keyed by the public provider name
         */
        public Map<String, Provider> getProviders() {
            return providers;
        }

        /**
         * Replaces the provider map during configuration binding.
         *
         * @param providers provider map
         */
        public void setProviders(Map<String, Provider> providers) {
            this.providers = providers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(providers);
        }

        /**
         * Returns one allow-listed provider, if configured.
         *
         * @param name provider name
         * @return provider configuration
         */
        public Provider provider(String name) {
            return name == null ? null : providers.get(name);
        }

        /**
         * Confirms that state is bounded and usable.
         *
         * @return true when the TTL is positive
         */
        @AssertTrue(message = "callbackStateTtl must be positive")
        public boolean isCallbackStateTtlPositive() {
            return callbackStateTtl != null && !callbackStateTtl.isZero() && !callbackStateTtl.isNegative();
        }

        private Duration positiveDuration(Duration value, String name) {
            if (value == null || value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return value;
        }
    }

    /**
     * One confidential OIDC provider's protocol endpoints and client credentials.
     */
    public static class Provider {

        @NotBlank(message = "issuer must be configured")
        @Pattern(regexp = "https://[^\\s]+", message = "issuer must be an absolute https URL")
        private String issuer;
        @NotBlank(message = "authorizationUri must be configured")
        @Pattern(regexp = "https://[^\\s]+", message = "authorizationUri must be an absolute https URL")
        private String authorizationUri;
        @NotBlank(message = "tokenUri must be configured")
        @Pattern(regexp = "https://[^\\s]+", message = "tokenUri must be an absolute https URL")
        private String tokenUri;
        @NotBlank(message = "jwkSetUri must be configured")
        @Pattern(regexp = "https://[^\\s]+", message = "jwkSetUri must be an absolute https URL")
        private String jwkSetUri;
        @NotBlank(message = "clientId must be configured")
        private String clientId;
        @NotBlank(message = "clientSecret must be configured")
        private String clientSecret;
        @NotBlank(message = "redirectUri must be configured")
        @Pattern(
                regexp = "(?i)(https://[^\\s]+|http://(?:localhost|127\\.0\\.0\\.1)(?::\\d+)?(?:/[^\\s]*)?)",
                message = "redirectUri must be an absolute https URL or loopback http URL")
        private String redirectUri;
        @NotBlank(message = "scope must be configured")
        private String scope = "openid profile email";

        /**
         * Creates an empty provider for external configuration binding.
         */
        public Provider() {
        }

        /**
         * Returns the provider issuer.
         *
         * @return issuer URL
         */
        public String getIssuer() {
            return issuer;
        }

        /**
         * Sets the provider issuer.
         *
         * @param issuer issuer URL
         */
        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        /**
         * Returns the authorization endpoint.
         *
         * @return authorization URL
         */
        public String getAuthorizationUri() {
            return authorizationUri;
        }

        /**
         * Sets the authorization endpoint.
         *
         * @param authorizationUri authorization URL
         */
        public void setAuthorizationUri(String authorizationUri) {
            this.authorizationUri = authorizationUri;
        }

        /**
         * Returns the token endpoint.
         *
         * @return token URL
         */
        public String getTokenUri() {
            return tokenUri;
        }

        /**
         * Sets the token endpoint.
         *
         * @param tokenUri token URL
         */
        public void setTokenUri(String tokenUri) {
            this.tokenUri = tokenUri;
        }

        /**
         * Returns the provider JWK set endpoint.
         *
         * @return JWK set URL
         */
        public String getJwkSetUri() {
            return jwkSetUri;
        }

        /**
         * Sets the provider JWK set endpoint.
         *
         * @param jwkSetUri JWK set URL
         */
        public void setJwkSetUri(String jwkSetUri) {
            this.jwkSetUri = jwkSetUri;
        }

        /**
         * Returns the confidential client identifier.
         *
         * @return client identifier
         */
        public String getClientId() {
            return clientId;
        }

        /**
         * Sets the confidential client identifier.
         *
         * @param clientId client identifier
         */
        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        /**
         * Returns the confidential client secret.
         *
         * @return client secret
         */
        public String getClientSecret() {
            return clientSecret;
        }

        /**
         * Sets the confidential client secret.
         *
         * @param clientSecret client secret
         */
        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }

        /**
         * Returns the callback endpoint.
         *
         * @return redirect URL
         */
        public String getRedirectUri() {
            return redirectUri;
        }

        /**
         * Sets the callback endpoint.
         *
         * @param redirectUri redirect URL
         */
        public void setRedirectUri(String redirectUri) {
            this.redirectUri = redirectUri;
        }

        /**
         * Returns the requested OIDC scope.
         *
         * @return space-delimited scope
         */
        public String getScope() {
            return scope;
        }

        /**
         * Sets the requested OIDC scope and requires the OpenID scope.
         *
         * @param scope space-delimited scope
         */
        public void setScope(String scope) {
            if (scope == null || scope.isBlank()
                    || !Set.of(scope.trim().split("\\s+")).contains("openid")) {
                throw new IllegalArgumentException("scope must be non-blank and include openid");
            }
            this.scope = scope.trim();
        }
    }

    /**
     * WebAuthn relying-party configuration and bounded challenge policy.
     */
    public static class WebAuthn {

        @NotNull(message = "challengeTtl must be configured")
        private Duration challengeTtl = Duration.ofMinutes(5);
        @NotBlank(message = "rpId must be configured")
        private String rpId = "localhost";
        @NotBlank(message = "rpName must be configured")
        private String rpName = "LifeOS";
        @NotEmpty(message = "allowedOrigins must contain at least one origin")
        private Set<String> allowedOrigins = new LinkedHashSet<>(Set.of("http://localhost:4200"));
        @NotNull(message = "userVerification must be configured")
        private UserVerificationRequirement userVerification = UserVerificationRequirement.REQUIRED;

        /**
         * Creates WebAuthn settings with safe local-development defaults.
         */
        public WebAuthn() {
        }

        /**
         * Returns the lifetime of one single-use assertion request.
         *
         * @return challenge TTL
         */
        public Duration getChallengeTtl() {
            return challengeTtl;
        }

        /**
         * Sets the challenge lifetime.
         *
         * @param challengeTtl challenge TTL
         */
        public void setChallengeTtl(Duration challengeTtl) {
            if (challengeTtl == null || challengeTtl.isZero() || challengeTtl.isNegative()) {
                throw new IllegalArgumentException("challengeTtl must be positive");
            }
            this.challengeTtl = challengeTtl;
        }

        /**
         * Returns the WebAuthn relying-party identifier.
         *
         * @return RP ID
         */
        public String getRpId() {
            return rpId;
        }

        /**
         * Sets the WebAuthn relying-party identifier.
         *
         * @param rpId RP ID
         */
        public void setRpId(String rpId) {
            if (rpId == null || rpId.isBlank()) {
                throw new IllegalArgumentException("rpId must not be blank");
            }
            this.rpId = rpId.trim();
        }

        /**
         * Returns the display name advertised to authenticators.
         *
         * @return RP display name
         */
        public String getRpName() {
            return rpName;
        }

        /**
         * Sets the display name advertised to authenticators.
         *
         * @param rpName RP display name
         */
        public void setRpName(String rpName) {
            if (rpName == null || rpName.isBlank()) {
                throw new IllegalArgumentException("rpName must not be blank");
            }
            this.rpName = rpName.trim();
        }

        /**
         * Returns the exact browser origins accepted by the relying party.
         *
         * @return allowed origins
         */
        public Set<String> getAllowedOrigins() {
            return allowedOrigins;
        }

        /**
         * Replaces the exact browser-origin allow-list.
         *
         * @param allowedOrigins browser origins
         */
        public void setAllowedOrigins(Set<String> allowedOrigins) {
            if (allowedOrigins == null || allowedOrigins.isEmpty()) {
                throw new IllegalArgumentException("allowedOrigins must contain at least one origin");
            }
            this.allowedOrigins = new LinkedHashSet<>(allowedOrigins);
        }

        /**
         * Returns the user-verification policy required during assertions.
         *
         * @return user-verification policy
         */
        public UserVerificationRequirement getUserVerification() {
            return userVerification;
        }

        /**
         * Sets the user-verification policy required during assertions.
         *
         * @param userVerification user-verification policy
         */
        public void setUserVerification(UserVerificationRequirement userVerification) {
            if (userVerification == null) {
                throw new IllegalArgumentException("userVerification must be configured");
            }
            this.userVerification = userVerification;
        }

        /**
         * Confirms that the configured challenge TTL is strictly positive.
         *
         * @return true when the challenge can be bounded
         */
        @AssertTrue(message = "challengeTtl must be positive")
        public boolean isChallengeTtlPositive() {
            return challengeTtl != null && !challengeTtl.isZero() && !challengeTtl.isNegative();
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
     * Secret-backed settings for public account-registration retry handling.
     */
    public static class Registration {

        private static final int MINIMUM_SECRET_BYTES = 32;

        @NotBlank(message = "idempotencySecret must be configured")
        private String idempotencySecret;

        /** Creates empty settings so deployments must supply a separate registration secret. */
        public Registration() {
        }

        /**
         * Returns the secret used only to HMAC registration idempotency material.
         *
         * @return registration idempotency secret
         */
        public String getIdempotencySecret() {
            return idempotencySecret;
        }

        /**
         * Sets the registration idempotency secret from deployment configuration.
         *
         * @param idempotencySecret secret with at least 32 UTF-8 bytes
         */
        public void setIdempotencySecret(String idempotencySecret) {
            if (idempotencySecret == null || idempotencySecret.isBlank()
                    || idempotencySecret.getBytes(StandardCharsets.UTF_8).length < MINIMUM_SECRET_BYTES) {
                throw new IllegalArgumentException("idempotencySecret must contain at least 32 bytes");
            }
            this.idempotencySecret = idempotencySecret;
        }
    }

    /**
     * JWT signing properties.
     */
    public static class Jwt {

        @NotBlank(message = "issuer must be configured")
        private String issuer = "lifeos-identity";
        @NotBlank(message = "audience must be configured")
        private String audience = "lifeos";
        private String signingSecret;
        private String signingKeyId = "lifeos-identity-dev";
        private String privateKeyPem;
        private String publicKeyPem;
        private String replayEncryptionSecret;
        @NotNull(message = "refreshTokenTtl must be configured")
        private Duration refreshTokenTtl = Duration.ofDays(30);
        @NotNull(message = "refreshFamilyTtl must be configured")
        private Duration refreshFamilyTtl = Duration.ofDays(90);
        @NotNull(message = "refreshIdleTtl must be configured")
        private Duration refreshIdleTtl = Duration.ofDays(14);
        @NotNull(message = "refreshReplayTtl must be configured")
        private Duration refreshReplayTtl = Duration.ofSeconds(30);
        @Min(value = 1, message = "maxRefreshReplayRecordsPerFamily must be positive")
        private int maxRefreshReplayRecordsPerFamily = 64;
        @NotNull(message = "clockSkew must be configured")
        private Duration clockSkew = Duration.ofSeconds(60);

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
         * Returns the audience required by LifeOS protected services.
         *
         * @return JWT audience
         */
        public String getAudience() {
            return audience;
        }

        /**
         * Sets the JWT audience.
         *
         * @param audience audience value
         */
        public void setAudience(String audience) {
            if (audience == null || audience.isBlank()) {
                throw new IllegalArgumentException("audience must not be blank");
            }
            this.audience = audience;
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
            if (signingSecret != null && !signingSecret.isBlank()
                    && signingSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
                throw new IllegalArgumentException("signingSecret must contain at least 32 bytes");
            }
            this.signingSecret = signingSecret;
        }

        /**
         * Returns the active signing key identifier.
         *
         * @return key id
         */
        public String getSigningKeyId() {
            return signingKeyId;
        }

        /**
         * Sets the active signing key identifier.
         *
         * @param signingKeyId key id
         */
        public void setSigningKeyId(String signingKeyId) {
            if (signingKeyId == null || signingKeyId.isBlank()) {
                throw new IllegalArgumentException("signingKeyId must not be blank");
            }
            this.signingKeyId = signingKeyId;
        }

        /**
         * Returns the configured PKCS#8 RSA private key PEM.
         *
         * @return private key PEM, or {@code null} when HMAC signing is selected
         */
        public String getPrivateKeyPem() {
            return privateKeyPem;
        }

        /**
         * Sets the PKCS#8 RSA private key PEM.
         *
         * @param privateKeyPem private key PEM
         */
        public void setPrivateKeyPem(String privateKeyPem) {
            this.privateKeyPem = privateKeyPem;
        }

        /**
         * Returns the configured X.509 RSA public key PEM.
         *
         * @return public key PEM, or {@code null} when HMAC signing is selected
         */
        public String getPublicKeyPem() {
            return publicKeyPem;
        }

        /**
         * Sets the X.509 RSA public key PEM.
         *
         * @param publicKeyPem public key PEM
         */
        public void setPublicKeyPem(String publicKeyPem) {
            this.publicKeyPem = publicKeyPem;
        }

        /**
         * Returns the dedicated replay-envelope encryption secret.
         *
         * @return replay encryption secret
         */
        public String getReplayEncryptionSecret() {
            return replayEncryptionSecret;
        }

        /**
         * Sets the dedicated replay-envelope encryption secret.
         *
         * @param replayEncryptionSecret secret with at least 32 UTF-8 bytes
         */
        public void setReplayEncryptionSecret(String replayEncryptionSecret) {
            if (replayEncryptionSecret == null
                    || replayEncryptionSecret.isBlank()
                    || replayEncryptionSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
                throw new IllegalArgumentException(
                        "replayEncryptionSecret must contain at least 32 bytes");
            }
            this.replayEncryptionSecret = replayEncryptionSecret;
        }

        /**
         * Returns the maximum lifetime of an individual refresh credential.
         *
         * @return refresh-token TTL
         */
        public Duration getRefreshTokenTtl() {
            return refreshTokenTtl;
        }

        /**
         * Sets the maximum lifetime of an individual refresh credential.
         *
         * @param refreshTokenTtl refresh-token TTL
         */
        public void setRefreshTokenTtl(Duration refreshTokenTtl) {
            this.refreshTokenTtl = positiveDuration(refreshTokenTtl, "refreshTokenTtl");
        }

        /**
         * Returns the absolute lifetime of a refresh-token family.
         *
         * @return family TTL
         */
        public Duration getRefreshFamilyTtl() {
            return refreshFamilyTtl;
        }

        /**
         * Sets the absolute lifetime of a refresh-token family.
         *
         * @param refreshFamilyTtl family TTL
         */
        public void setRefreshFamilyTtl(Duration refreshFamilyTtl) {
            this.refreshFamilyTtl = positiveDuration(refreshFamilyTtl, "refreshFamilyTtl");
        }

        /**
         * Returns the idle lifetime after the last successful refresh.
         *
         * @return idle TTL
         */
        public Duration getRefreshIdleTtl() {
            return refreshIdleTtl;
        }

        /**
         * Sets the idle lifetime after the last successful refresh.
         *
         * @param refreshIdleTtl idle TTL
         */
        public void setRefreshIdleTtl(Duration refreshIdleTtl) {
            this.refreshIdleTtl = positiveDuration(refreshIdleTtl, "refreshIdleTtl");
        }

        /**
         * Returns the lifetime of a committed idempotent retry envelope.
         *
         * @return replay envelope TTL
         */
        public Duration getRefreshReplayTtl() {
            return refreshReplayTtl;
        }

        /**
         * Sets the lifetime of a committed idempotent retry envelope.
         *
         * @param refreshReplayTtl replay envelope TTL
         */
        public void setRefreshReplayTtl(Duration refreshReplayTtl) {
            this.refreshReplayTtl = positiveDuration(refreshReplayTtl, "refreshReplayTtl");
        }

        /**
         * Returns the bounded replay-evidence count per family.
         *
         * @return maximum replay records
         */
        public int getMaxRefreshReplayRecordsPerFamily() {
            return maxRefreshReplayRecordsPerFamily;
        }

        /**
         * Sets the bounded replay-evidence count per family.
         *
         * @param maxRefreshReplayRecordsPerFamily maximum replay records
         */
        public void setMaxRefreshReplayRecordsPerFamily(int maxRefreshReplayRecordsPerFamily) {
            if (maxRefreshReplayRecordsPerFamily < 1) {
                throw new IllegalArgumentException("maxRefreshReplayRecordsPerFamily must be positive");
            }
            this.maxRefreshReplayRecordsPerFamily = maxRefreshReplayRecordsPerFamily;
        }

        /**
         * Returns the permitted JWT timestamp clock skew.
         *
         * @return clock skew
         */
        public Duration getClockSkew() {
            return clockSkew;
        }

        /**
         * Sets the permitted JWT timestamp clock skew.
         *
         * @param clockSkew clock skew
         */
        public void setClockSkew(Duration clockSkew) {
            this.clockSkew = positiveDuration(clockSkew, "clockSkew");
        }

        /**
         * Validates all JWT and refresh lifetime settings.
         *
         * @return true when every lifetime is positive
         */
        @AssertTrue(message = "JWT lifetime settings must be positive")
        public boolean areLifetimeSettingsPositive() {
            return isPositive(refreshTokenTtl) && isPositive(refreshFamilyTtl)
                    && isPositive(refreshIdleTtl) && isPositive(refreshReplayTtl)
                    && isPositive(clockSkew);
        }

        /**
         * Validates that one supported JWT signing mode is configured.
         *
         * @return true when HMAC or a complete RSA key pair is configured
         */
        @AssertTrue(message = "an HMAC secret or an RSA key pair must be configured")
        public boolean isSigningMaterialConfigured() {
            boolean hmacConfigured = signingSecret != null && !signingSecret.isBlank();
            boolean rsaConfigured = privateKeyPem != null && !privateKeyPem.isBlank()
                    && publicKeyPem != null && !publicKeyPem.isBlank();
            return hmacConfigured || rsaConfigured;
        }

        /**
         * Confirms that replay envelopes have an independently configured key.
         *
         * @return true when the dedicated secret meets the entropy floor
         */
        @AssertTrue(message = "replayEncryptionSecret must contain at least 32 bytes")
        public boolean isReplayEncryptionConfigured() {
            return replayEncryptionSecret != null
                    && !replayEncryptionSecret.isBlank()
                    && replayEncryptionSecret.getBytes(StandardCharsets.UTF_8).length >= 32;
        }

        private Duration positiveDuration(Duration value, String name) {
            if (!isPositive(value)) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return value;
        }

        private boolean isPositive(Duration value) {
            return value != null && !value.isZero() && !value.isNegative();
        }
    }
}
