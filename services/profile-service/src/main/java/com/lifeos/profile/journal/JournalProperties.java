package com.lifeos.profile.journal;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Explicitly opt-in, bounded MongoDB storage for owner-scoped journals and notes. */
@ConfigurationProperties(prefix = "profile.journal")
@Validated
public class JournalProperties {

    private boolean enabled;

    @NotBlank(message = "profile.journal.uri must be configured")
    private String uri = "mongodb://localhost:27017";

    @NotBlank
    private String database = "lifeos_profile";

    @NotBlank
    private String collection = "journal_entries";

    private String encryptionKey = "";

    @Min(1)
    @Max(100_000)
    private int maxEntriesPerOwner = 10_000;

    @Min(256)
    @Max(131_072)
    private int maxContentBytes = 65_536;

    @Min(1)
    @Max(100)
    private int maxPageSize = 50;

    @NotNull
    private Duration timeout = Duration.ofSeconds(2);

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

    public String getCollection() {
        return collection;
    }

    public void setCollection(String collection) {
        this.collection = collection;
    }

    public String getEncryptionKey() {
        return encryptionKey;
    }

    public void setEncryptionKey(String encryptionKey) {
        this.encryptionKey = encryptionKey;
    }

    public int getMaxEntriesPerOwner() {
        return maxEntriesPerOwner;
    }

    public void setMaxEntriesPerOwner(int maxEntriesPerOwner) {
        this.maxEntriesPerOwner = maxEntriesPerOwner;
    }

    public int getMaxContentBytes() {
        return maxContentBytes;
    }

    public void setMaxContentBytes(int maxContentBytes) {
        this.maxContentBytes = maxContentBytes;
    }

    public int getMaxPageSize() {
        return maxPageSize;
    }

    public void setMaxPageSize(int maxPageSize) {
        this.maxPageSize = maxPageSize;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    @AssertTrue(message = "profile.journal.uri must be mongodb+srv or loopback mongodb://")
    public boolean isUriValid() {
        if (uri == null || uri.isBlank()) {
            return false;
        }
        try {
            URI parsed = URI.create(uri);
            if ("mongodb+srv".equalsIgnoreCase(parsed.getScheme())) {
                return parsed.getHost() != null;
            }
            return "mongodb".equalsIgnoreCase(parsed.getScheme())
                    && parsed.getHost() != null
                    && isLoopback(parsed.getHost());
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    @AssertTrue(message = "enabled journal storage requires a base64 AES-256 encryption key")
    public boolean isEncryptionKeyValid() {
        if (!enabled) {
            return true;
        }
        try {
            return Base64.getDecoder().decode(encryptionKey).length == 32;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    @AssertTrue(message = "journal timeout must be between one millisecond and 30 seconds")
    public boolean isTimeoutValid() {
        return timeout != null
                && !timeout.isNegative()
                && !timeout.isZero()
                && timeout.compareTo(Duration.ofMillis(1)) >= 0
                && timeout.compareTo(Duration.ofSeconds(30)) <= 0;
    }

    private static boolean isLoopback(String host) {
        if ("localhost".equalsIgnoreCase(host)) {
            return true;
        }
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (UnknownHostException ignored) {
            return false;
        }
    }
}
