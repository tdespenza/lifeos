package com.lifeos.identity.auth;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Bounded, secret-backed one-time passkey recovery-code policy. */
@ConfigurationProperties(prefix = "identity.passkey-recovery")
@Validated
public class PasskeyRecoveryProperties {

    @NotBlank
    @Size(min = 32, max = 512)
    private String hmacSecret;

    @NotNull
    private Duration codeTtl = Duration.ofMinutes(15);

    @Min(4)
    @Max(16)
    private int codeCount = 8;

    public String getHmacSecret() {
        return hmacSecret;
    }

    public void setHmacSecret(String hmacSecret) {
        this.hmacSecret = hmacSecret;
    }

    public Duration getCodeTtl() {
        return codeTtl;
    }

    public void setCodeTtl(Duration codeTtl) {
        this.codeTtl = codeTtl;
    }

    public int getCodeCount() {
        return codeCount;
    }

    public void setCodeCount(int codeCount) {
        this.codeCount = codeCount;
    }

    @AssertTrue(message = "passkey recovery code TTL must be between one minute and 24 hours")
    public boolean isCodeTtlValid() {
        return codeTtl != null
                && codeTtl.compareTo(Duration.ofMinutes(1)) >= 0
                && codeTtl.compareTo(Duration.ofHours(24)) <= 0;
    }
}
