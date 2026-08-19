package com.lifeos.taskgoal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Credentials for the explicit Media post-session follow-up-task boundary. */
@ConfigurationProperties(prefix = "task-goal.media-tool")
public class TaskGoalMediaToolProperties {

    private String workloadIdentity = "media-service";
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
