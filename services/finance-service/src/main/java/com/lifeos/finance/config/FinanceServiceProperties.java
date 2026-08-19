package com.lifeos.finance.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Deployment-owned request bounds and HMAC secrets for the Finance service. */
@ConfigurationProperties(prefix = "finance")
@Validated
public class FinanceServiceProperties {

    @NotNull(message = "inboundRequestTimeout must be configured")
    private Duration inboundRequestTimeout = Duration.ofSeconds(10);

    @Min(value = 1, message = "maxInboundBodyBytes must be positive")
    @Max(value = 1_048_576, message = "maxInboundBodyBytes must be no greater than one megabyte")
    private long maxInboundBodyBytes = 65_536L;

    @Min(value = 1, message = "maxConcurrentRequests must be positive")
    @Max(value = 4096, message = "maxConcurrentRequests must be bounded")
    private int maxConcurrentRequests = 128;

    @NotBlank(message = "idempotencySecret (FINANCE_IDEMPOTENCY_SECRET) must be configured and non-blank")
    private String idempotencySecret;

    @NotBlank(message = "auditClientFingerprintSecret (FINANCE_AUDIT_CLIENT_FINGERPRINT_SECRET) must be configured and non-blank")
    private String auditClientFingerprintSecret;

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

    public String getIdempotencySecret() {
        return idempotencySecret;
    }

    public void setIdempotencySecret(String idempotencySecret) {
        this.idempotencySecret = idempotencySecret;
    }

    public String getAuditClientFingerprintSecret() {
        return auditClientFingerprintSecret;
    }

    public void setAuditClientFingerprintSecret(String auditClientFingerprintSecret) {
        this.auditClientFingerprintSecret = auditClientFingerprintSecret;
    }

    @AssertTrue(message = "inboundRequestTimeout must be at least one millisecond and no greater than 60 seconds")
    public boolean isInboundRequestTimeoutValid() {
        return inboundRequestTimeout != null
                && !inboundRequestTimeout.isZero()
                && !inboundRequestTimeout.isNegative()
                && inboundRequestTimeout.compareTo(Duration.ofMillis(1)) >= 0
                && inboundRequestTimeout.compareTo(Duration.ofSeconds(60)) <= 0;
    }
}
