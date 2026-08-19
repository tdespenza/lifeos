package com.lifeos.trustledger.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Deployment-owned resource bounds for Trust Ledger public requests. */
@ConfigurationProperties(prefix = "trust-ledger")
@Validated
public class TrustLedgerServiceProperties {

    private static final long MIN_DOCUMENT_BYTES = 1L;
    private static final long MAX_DOCUMENT_BYTES = 100L * 1024L * 1024L;

    @NotNull(message = "inboundRequestTimeout must be configured")
    private Duration inboundRequestTimeout = Duration.ofSeconds(10);

    @Min(value = MIN_DOCUMENT_BYTES, message = "maxDocumentBytes must be positive")
    @Max(value = MAX_DOCUMENT_BYTES, message = "maxDocumentBytes must not exceed 100 MiB")
    private long maxDocumentBytes = MAX_DOCUMENT_BYTES;

    @Min(value = 1, message = "maxMerkleLeaves must be positive")
    @Max(value = 10_000, message = "maxMerkleLeaves must not exceed 10000")
    private int maxMerkleLeaves = 10_000;

    public Duration getInboundRequestTimeout() {
        return inboundRequestTimeout;
    }

    public void setInboundRequestTimeout(Duration inboundRequestTimeout) {
        this.inboundRequestTimeout = inboundRequestTimeout;
    }

    public long getMaxDocumentBytes() {
        return maxDocumentBytes;
    }

    public void setMaxDocumentBytes(long maxDocumentBytes) {
        this.maxDocumentBytes = maxDocumentBytes;
    }

    public int getMaxMerkleLeaves() {
        return maxMerkleLeaves;
    }

    public void setMaxMerkleLeaves(int maxMerkleLeaves) {
        this.maxMerkleLeaves = maxMerkleLeaves;
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
