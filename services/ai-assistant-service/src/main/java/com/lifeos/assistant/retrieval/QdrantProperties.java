package com.lifeos.assistant.retrieval;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Bounded, opt-in Qdrant connection and retrieval limits. */
@ConfigurationProperties(prefix = "qdrant")
@Validated
public class QdrantProperties {

    private boolean enabled;

    @NotBlank(message = "baseUrl must be configured")
    private String baseUrl = "http://localhost:6333";

    @NotBlank(message = "collection must be configured")
    private String collection = "lifeos_documents_v1";

    private String apiKey;

    @Min(8)
    @Max(1024)
    private int vectorSize = 32;

    @Min(1)
    @Max(32)
    private int maxResults = 8;

    @Min(1)
    @Max(256)
    private int maxChunksPerDocument = 64;

    @Min(1)
    @Max(4096)
    private int maxChunkCharacters = 1600;

    @NotNull
    private Duration timeout = Duration.ofSeconds(2);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getCollection() {
        return collection;
    }

    public void setCollection(String collection) {
        this.collection = collection;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public int getVectorSize() {
        return vectorSize;
    }

    public void setVectorSize(int vectorSize) {
        this.vectorSize = vectorSize;
    }

    public int getMaxResults() {
        return maxResults;
    }

    public void setMaxResults(int maxResults) {
        this.maxResults = maxResults;
    }

    public int getMaxChunksPerDocument() {
        return maxChunksPerDocument;
    }

    public void setMaxChunksPerDocument(int maxChunksPerDocument) {
        this.maxChunksPerDocument = maxChunksPerDocument;
    }

    public int getMaxChunkCharacters() {
        return maxChunkCharacters;
    }

    public void setMaxChunkCharacters(int maxChunkCharacters) {
        this.maxChunkCharacters = maxChunkCharacters;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    @AssertTrue(message = "baseUrl must be an absolute HTTPS URL unless its host is loopback")
    public boolean isBaseUrlValid() {
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(baseUrl);
            if (!uri.isAbsolute() || uri.getHost() == null) {
                return false;
            }
            return "https".equalsIgnoreCase(uri.getScheme())
                    || ("http".equalsIgnoreCase(uri.getScheme()) && isLoopback(uri.getHost()));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    @AssertTrue(message = "enabled Qdrant configuration requires a bounded collection and API key when supplied")
    public boolean isEnabledConfigurationValid() {
        if (collection == null || !collection.matches("[a-zA-Z][a-zA-Z0-9_-]{0,63}")) {
            return false;
        }
        return !enabled || (apiKey == null || apiKey.length() <= 512);
    }

    @AssertTrue(message = "Qdrant timeout must be between one millisecond and 30 seconds")
    public boolean isTimeoutValid() {
        return timeout != null
                && !timeout.isZero()
                && !timeout.isNegative()
                && timeout.compareTo(Duration.ofMillis(1)) >= 0
                && timeout.compareTo(Duration.ofSeconds(30)) <= 0;
    }

    private static boolean isLoopback(String host) {
        if ("localhost".equalsIgnoreCase(host)) {
            return true;
        }
        try {
            return InetAddress.getByName(host.replace("[", "").replace("]", "")).isLoopbackAddress();
        } catch (UnknownHostException ignored) {
            return false;
        }
    }
}
