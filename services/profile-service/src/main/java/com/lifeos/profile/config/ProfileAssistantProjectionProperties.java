package com.lifeos.profile.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Separate workload credential for the opt-in AI journal projection. */
@ConfigurationProperties(prefix = "profile.assistant-projection")
public class ProfileAssistantProjectionProperties {

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
