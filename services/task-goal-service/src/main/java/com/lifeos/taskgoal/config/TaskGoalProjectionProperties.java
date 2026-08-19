package com.lifeos.taskgoal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Workload credentials accepted by the narrow Calendar ownership projection.
 *
 * <p>The token has no default and the endpoint fails closed when it is absent. Keeping this
 * boundary separate from the TaskGoal-to-Identity credentials prevents a caller from reusing the
 * service's own authority credential as an inbound trust credential.
 */
@ConfigurationProperties(prefix = "task-goal.projection")
public class TaskGoalProjectionProperties {

    private String workloadIdentity = "calendar-service";
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
