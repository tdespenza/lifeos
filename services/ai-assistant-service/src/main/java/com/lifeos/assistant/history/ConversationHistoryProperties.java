package com.lifeos.assistant.history;

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

/** Explicit opt-in encrypted MongoDB history controls. */
@ConfigurationProperties(prefix = "ai-assistant.conversation-history")
@Validated
public class ConversationHistoryProperties {

    private boolean enabled;

    @NotBlank
    private String uri = "mongodb://localhost:27017/lifeos_ai_history";

    @NotBlank
    private String database = "lifeos_ai_history";

    private String encryptionKey;

    @NotBlank
    private String collection = "conversation_messages";

    @Min(1)
    @Max(1000)
    private int maxEntriesPerConversation = 100;

    @Min(1)
    @Max(365)
    private int retentionDays = 30;

    @Min(1024)
    @Max(131072)
    private int maxEntryBytes = 32768;

    @NotNull
    private Duration connectTimeout = Duration.ofSeconds(2);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUri() {
        return uri;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public String getEncryptionKey() {
        return encryptionKey;
    }

    public void setEncryptionKey(String encryptionKey) {
        this.encryptionKey = encryptionKey;
    }

    public String getCollection() {
        return collection;
    }

    public void setCollection(String collection) {
        this.collection = collection;
    }

    public int getMaxEntriesPerConversation() {
        return maxEntriesPerConversation;
    }

    public void setMaxEntriesPerConversation(int maxEntriesPerConversation) {
        this.maxEntriesPerConversation = maxEntriesPerConversation;
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }

    public int getMaxEntryBytes() {
        return maxEntryBytes;
    }

    public void setMaxEntryBytes(int maxEntryBytes) {
        this.maxEntryBytes = maxEntryBytes;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    @AssertTrue(message = "MongoDB URI must be mongodb(s) and loopback for plaintext")
    public boolean isUriValid() {
        if (uri == null || uri.isBlank()) {
            return false;
        }
        try {
            URI parsed = URI.create(uri);
            if (!parsed.isAbsolute() || parsed.getHost() == null) {
                return false;
            }
            return "mongodb+srv".equalsIgnoreCase(parsed.getScheme())
                    || ("mongodb".equalsIgnoreCase(parsed.getScheme()) && isLoopback(parsed.getHost()));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    @AssertTrue(message = "enabled MongoDB history requires a base64 AES-256 key")
    public boolean isEncryptionKeyValid() {
        if (!enabled) {
            return true;
        }
        try {
            return encryptionKey != null && java.util.Base64.getDecoder().decode(encryptionKey).length == 32;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    @AssertTrue(message = "MongoDB connectTimeout must be between one millisecond and 30 seconds")
    public boolean isConnectTimeoutValid() {
        return connectTimeout != null
                && !connectTimeout.isZero()
                && !connectTimeout.isNegative()
                && connectTimeout.compareTo(Duration.ofMillis(1)) >= 0
                && connectTimeout.compareTo(Duration.ofSeconds(30)) <= 0;
    }

    private static boolean isLoopback(String host) {
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (UnknownHostException ignored) {
            return "localhost".equalsIgnoreCase(host);
        }
    }
}
