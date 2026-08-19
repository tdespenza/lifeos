package com.lifeos.documentvault.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Deployment-owned bounds for uploads, servlet admission, and idempotent command hashing. */
@ConfigurationProperties(prefix = "document-vault")
@Validated
public class DocumentVaultServiceProperties {

    @NotNull(message = "inboundRequestTimeout must be configured")
    private Duration inboundRequestTimeout = Duration.ofSeconds(30);

    @NotNull(message = "uploadDeadline must be configured")
    private Duration uploadDeadline = Duration.ofSeconds(30);

    @Min(value = 1, message = "maxUploadBytes must be positive")
    @Max(value = 52_428_800, message = "maxUploadBytes must be no greater than 50 MiB")
    private long maxUploadBytes = 10_485_760L;

    @Min(value = 1, message = "maxInboundBodyBytes must be positive")
    @Max(value = 53_477_376, message = "maxInboundBodyBytes must be no greater than 51 MiB")
    private long maxInboundBodyBytes = 11_010_048L;

    @Min(value = 1, message = "maxConcurrentRequests must be positive")
    @Max(value = 4096, message = "maxConcurrentRequests must be bounded")
    private int maxConcurrentRequests = 64;

    @Min(value = 100, message = "maxSearchCatalogEntries must be at least 100")
    @Max(value = 20_000, message = "maxSearchCatalogEntries must be no greater than 20000")
    private int maxSearchCatalogEntries = 10_000;

    @NotBlank(message = "idempotencySecret (DOCUMENT_VAULT_IDEMPOTENCY_SECRET) must be configured")
    private String idempotencySecret;

    @NotBlank(message = "auditClientFingerprintSecret must be configured")
    private String auditClientFingerprintSecret;

    public Duration getInboundRequestTimeout() {
        return inboundRequestTimeout;
    }

    public void setInboundRequestTimeout(Duration inboundRequestTimeout) {
        this.inboundRequestTimeout = inboundRequestTimeout;
    }

    public Duration getUploadDeadline() {
        return uploadDeadline;
    }

    public void setUploadDeadline(Duration uploadDeadline) {
        this.uploadDeadline = uploadDeadline;
    }

    public long getMaxUploadBytes() {
        return maxUploadBytes;
    }

    public void setMaxUploadBytes(long maxUploadBytes) {
        this.maxUploadBytes = maxUploadBytes;
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

    public int getMaxSearchCatalogEntries() {
        return maxSearchCatalogEntries;
    }

    public void setMaxSearchCatalogEntries(int maxSearchCatalogEntries) {
        this.maxSearchCatalogEntries = maxSearchCatalogEntries;
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
        return isBoundedDuration(inboundRequestTimeout);
    }

    @AssertTrue(message = "uploadDeadline must be at least one millisecond and no greater than 60 seconds")
    public boolean isUploadDeadlineValid() {
        return isBoundedDuration(uploadDeadline);
    }

    @AssertTrue(message = "maxInboundBodyBytes must allow the configured upload plus multipart overhead")
    public boolean isInboundBodyLimitValid() {
        return maxInboundBodyBytes >= maxUploadBytes + 65_536L;
    }

    private static boolean isBoundedDuration(Duration value) {
        return value != null
                && !value.isZero()
                && !value.isNegative()
                && value.compareTo(Duration.ofMillis(1)) >= 0
                && value.compareTo(Duration.ofSeconds(60)) <= 0;
    }
}
