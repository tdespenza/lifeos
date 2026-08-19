package com.lifeos.taskgoal.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Deployment-owned resource bounds for the Task/Goal service's public HTTP listener.
 *
 * <p>This is intentionally separate from {@code identity}: the timeout applies before an
 * authorization flow is selected and therefore bounds every Task/Goal HTTP endpoint.
 */
@ConfigurationProperties(prefix = "task-goal")
@Validated
public class TaskGoalServiceProperties {

    @NotNull(message = "inboundRequestTimeout must be configured")
    private Duration inboundRequestTimeout = Duration.ofSeconds(10);

    /**
     * Returns the Tomcat timeout used for inbound connection and request-body upload reads.
     *
     * @return bounded inbound timeout
     */
    public Duration getInboundRequestTimeout() {
        return inboundRequestTimeout;
    }

    /**
     * Sets the Tomcat timeout used for inbound connection and request-body upload reads.
     *
     * @param inboundRequestTimeout bounded inbound timeout
     */
    public void setInboundRequestTimeout(Duration inboundRequestTimeout) {
        this.inboundRequestTimeout = inboundRequestTimeout;
    }

    /**
     * Ensures the timeout can be represented by Tomcat and remains a bounded denial-of-service
     * control rather than an unbounded connection wait.
     *
     * @return {@code true} when the configured timeout is usable
     */
    @AssertTrue(message = "inboundRequestTimeout must be at least one millisecond and no greater than 60 seconds")
    public boolean isInboundRequestTimeoutValid() {
        return inboundRequestTimeout != null
                && !inboundRequestTimeout.isZero()
                && !inboundRequestTimeout.isNegative()
                && inboundRequestTimeout.compareTo(Duration.ofMillis(1)) >= 0
                && inboundRequestTimeout.compareTo(Duration.ofSeconds(60)) <= 0;
    }
}
