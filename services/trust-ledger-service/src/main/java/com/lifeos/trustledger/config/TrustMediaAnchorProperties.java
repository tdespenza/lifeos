package com.lifeos.trustledger.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Workload credential accepted only by the internal Media session-summary anchor command. */
@ConfigurationProperties(prefix = "trust-ledger.media-anchor")
@Validated
public class TrustMediaAnchorProperties {

    @NotBlank
    private String workloadIdentity = "media-service";

    @NotBlank
    private String workloadToken;

    public String getWorkloadIdentity() { return workloadIdentity; }
    public void setWorkloadIdentity(String workloadIdentity) { this.workloadIdentity = workloadIdentity; }
    public String getWorkloadToken() { return workloadToken; }
    public void setWorkloadToken(String workloadToken) { this.workloadToken = workloadToken; }

    public boolean configured() {
        return workloadIdentity != null && !workloadIdentity.isBlank()
                && workloadToken != null && !workloadToken.isBlank();
    }
}
