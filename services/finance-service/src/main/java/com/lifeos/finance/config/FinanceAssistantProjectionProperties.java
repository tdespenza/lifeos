package com.lifeos.finance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Separate inbound workload credential for the assistant's aggregate-only Finance projection. */
@ConfigurationProperties(prefix = "finance.assistant-projection")
public class FinanceAssistantProjectionProperties {

    private String workloadIdentity = "ai-assistant-service";
    private String workloadToken = "";

    public String getWorkloadIdentity() {
        return workloadIdentity;
    }

    public void setWorkloadIdentity(String workloadIdentity) {
        this.workloadIdentity = workloadIdentity;
    }

    public String getWorkloadToken() {
        return workloadToken;
    }

    public void setWorkloadToken(String workloadToken) {
        this.workloadToken = workloadToken;
    }

    public boolean configured() {
        return workloadIdentity != null
                && !workloadIdentity.isBlank()
                && workloadToken != null
                && !workloadToken.isBlank();
    }
}
