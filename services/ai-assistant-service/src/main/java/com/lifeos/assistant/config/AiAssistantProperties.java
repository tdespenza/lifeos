package com.lifeos.assistant.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;
import java.time.Duration;
import java.net.URI;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Deployment-owned limits and audit key material for the assistant boundary. */
@ConfigurationProperties(prefix = "ai-assistant")
@Validated
public class AiAssistantProperties {

    @NotNull(message = "inboundRequestTimeout must be configured")
    private Duration inboundRequestTimeout = Duration.ofSeconds(8);

    @Min(value = 1, message = "maxInboundBodyBytes must be positive")
    @Max(value = 65_536, message = "maxInboundBodyBytes must be bounded")
    private long maxInboundBodyBytes = 16_384L;

    @Min(value = 1, message = "maxConcurrentRequests must be positive")
    @Max(value = 512, message = "maxConcurrentRequests must be bounded")
    private int maxConcurrentRequests = 64;

    @Min(value = 1, message = "maxMessageCharacters must be positive")
    @Max(value = 16_384, message = "maxMessageCharacters must be bounded")
    private int maxMessageCharacters = 4_096;

    @Min(value = 1, message = "maxEstimatedInputTokens must be positive")
    @Max(value = 4_096, message = "maxEstimatedInputTokens must be bounded")
    private int maxEstimatedInputTokens = 2_048;

    @Min(value = 1, message = "maxOutputTokens must be positive")
    @Max(value = 2_048, message = "maxOutputTokens must be bounded")
    private int maxOutputTokens = 512;

    @Min(value = 1, message = "maxConcurrentGenerations must be positive")
    @Max(value = 128, message = "maxConcurrentGenerations must be bounded")
    private int maxConcurrentGenerations = 16;

    @NotNull(message = "providerTimeout must be configured")
    private Duration providerTimeout = Duration.ofSeconds(5);

    @NotBlank(message = "auditHmacSecret (AI_ASSISTANT_AUDIT_HMAC_SECRET) must be configured and non-blank")
    private String auditHmacSecret;

    @Valid
    @NotNull
    private Provider provider = new Provider();

    public Duration getInboundRequestTimeout() {
        return inboundRequestTimeout;
    }

    public void setInboundRequestTimeout(Duration inboundRequestTimeout) {
        this.inboundRequestTimeout = inboundRequestTimeout;
    }

    public long getMaxInboundBodyBytes() {
        return maxInboundBodyBytes;
    }

    public void setMaxInboundBodyBytes(long maxInboundBodyBytes) {
        this.maxInboundBodyBytes = maxInboundBodyBytes;
    }

    public int getMaxConcurrentRequests() {
        return maxConcurrentRequests;
    }

    public void setMaxConcurrentRequests(int maxConcurrentRequests) {
        this.maxConcurrentRequests = maxConcurrentRequests;
    }

    public int getMaxMessageCharacters() {
        return maxMessageCharacters;
    }

    public void setMaxMessageCharacters(int maxMessageCharacters) {
        this.maxMessageCharacters = maxMessageCharacters;
    }

    public int getMaxEstimatedInputTokens() {
        return maxEstimatedInputTokens;
    }

    public void setMaxEstimatedInputTokens(int maxEstimatedInputTokens) {
        this.maxEstimatedInputTokens = maxEstimatedInputTokens;
    }

    public int getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(int maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }

    public int getMaxConcurrentGenerations() {
        return maxConcurrentGenerations;
    }

    public void setMaxConcurrentGenerations(int maxConcurrentGenerations) {
        this.maxConcurrentGenerations = maxConcurrentGenerations;
    }

    public Duration getProviderTimeout() {
        return providerTimeout;
    }

    public void setProviderTimeout(Duration providerTimeout) {
        this.providerTimeout = providerTimeout;
    }

    public String getAuditHmacSecret() {
        return auditHmacSecret;
    }

    public void setAuditHmacSecret(String auditHmacSecret) {
        this.auditHmacSecret = auditHmacSecret;
    }

    public Provider getProvider() {
        return provider;
    }

    public void setProvider(Provider provider) {
        this.provider = provider;
    }

    @AssertTrue(message = "inboundRequestTimeout must be between one millisecond and 60 seconds")
    public boolean isInboundRequestTimeoutValid() {
        return isBetween(inboundRequestTimeout, Duration.ofMillis(1), Duration.ofSeconds(60));
    }

    @AssertTrue(message = "providerTimeout must be between one millisecond and 30 seconds")
    public boolean isProviderTimeoutValid() {
        return isBetween(providerTimeout, Duration.ofMillis(1), Duration.ofSeconds(30));
    }

    private static boolean isBetween(Duration value, Duration lower, Duration upper) {
        return value != null
                && !value.isNegative()
                && !value.isZero()
                && value.compareTo(lower) >= 0
                && value.compareTo(upper) <= 0;
    }

    public enum ProviderMode {
        DISABLED,
        LOCAL_DETERMINISTIC,
        OPENAI_COMPATIBLE
    }

    /** Deployment-owned provider endpoint; disabled by default so local startup remains fail-closed. */
    public static class Provider {

        private ProviderMode mode = ProviderMode.DISABLED;
        private String baseUrl = "http://localhost:11434/v1";
        private String completionPath = "/chat/completions";
        private String apiKey;
        private String model = "llama3.2";
        @Min(value = 1_024, message = "provider maxResponseBytes must be at least 1024")
        @Max(value = 1_048_576, message = "provider maxResponseBytes must be at most 1 MiB")
        private int maxResponseBytes = 262_144;

        public ProviderMode getMode() {
            return mode;
        }

        public void setMode(ProviderMode mode) {
            this.mode = mode;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getCompletionPath() {
            return completionPath;
        }

        public void setCompletionPath(String completionPath) {
            this.completionPath = completionPath;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getMaxResponseBytes() {
            return maxResponseBytes;
        }

        public void setMaxResponseBytes(int maxResponseBytes) {
            this.maxResponseBytes = maxResponseBytes;
        }

        @AssertTrue(message = "enabled provider requires a safe baseUrl, completionPath, and model")
        public boolean isValidWhenEnabled() {
            if (mode == null || mode == ProviderMode.DISABLED) {
                return true;
            }
            if (mode == ProviderMode.LOCAL_DETERMINISTIC) {
                return model != null && !model.isBlank() && isSafeModel(model);
            }
            if (baseUrl == null || baseUrl.isBlank() || completionPath == null || completionPath.isBlank()
                    || model == null || model.isBlank()) {
                return false;
            }
            try {
                URI uri = URI.create(baseUrl.trim());
                if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                        || uri.getFragment() != null) {
                    return false;
                }
                boolean loopbackHttp = "http".equalsIgnoreCase(uri.getScheme())
                        && ("localhost".equalsIgnoreCase(uri.getHost())
                                || "127.0.0.1".equals(uri.getHost())
                                || "::1".equals(uri.getHost())
                                || "[::1]".equals(uri.getHost()));
                if (!loopbackHttp && !"https".equalsIgnoreCase(uri.getScheme())) {
                    return false;
                }
                String path = completionPath.trim();
                return path.startsWith("/") && !path.contains("..") && isSafeModel(model);
            } catch (IllegalArgumentException exception) {
                return false;
            }
        }

        private static boolean isSafeModel(String value) {
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            return normalized.length() <= 128 && normalized.matches("[a-z0-9][a-z0-9._:/-]*");
        }
    }
}
