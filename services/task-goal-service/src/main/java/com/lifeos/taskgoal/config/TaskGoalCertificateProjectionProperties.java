package com.lifeos.taskgoal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Separate inbound credential for the Trust Ledger's completed-goal projection. */
@ConfigurationProperties(prefix = "task-goal.certificate-projection")
public class TaskGoalCertificateProjectionProperties {

    private String workloadIdentity = "trust-ledger-service";
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
        return workloadIdentity != null && !workloadIdentity.isBlank()
                && workloadToken != null && !workloadToken.isBlank();
    }
}
