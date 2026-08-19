package com.lifeos.documentvault.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Bounded, opt-in local OCR settings. OCR is deliberately disabled by default because the
 * executable is deployment-owned and document pixels must never leave the local storage boundary.
 */
@ConfigurationProperties(prefix = "document-vault.ocr")
@Validated
public class DocumentVaultOcrProperties {

    private boolean enabled;

    @NotBlank(message = "executable must be configured when OCR is enabled")
    private String executable = "tesseract";

    @NotNull(message = "timeout must be configured")
    private Duration timeout = Duration.ofSeconds(5);

    @Min(value = 1_024, message = "maxOutputCharacters must be at least 1024")
    @Max(value = 65_536, message = "maxOutputCharacters must be no greater than 65536")
    private int maxOutputCharacters = 65_536;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getExecutable() {
        return executable;
    }

    public void setExecutable(String executable) {
        this.executable = executable;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public int getMaxOutputCharacters() {
        return maxOutputCharacters;
    }

    public void setMaxOutputCharacters(int maxOutputCharacters) {
        this.maxOutputCharacters = maxOutputCharacters;
    }

    @AssertTrue(message = "OCR timeout must be between one millisecond and 30 seconds")
    public boolean isTimeoutValid() {
        return timeout != null
                && !timeout.isZero()
                && !timeout.isNegative()
                && timeout.compareTo(Duration.ofMillis(1)) >= 0
                && timeout.compareTo(Duration.ofSeconds(30)) <= 0;
    }
}
