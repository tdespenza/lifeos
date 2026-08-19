package com.lifeos.taskgoal.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Verifies that Task/Goal inbound timeout configuration fails before startup when unsafe. */
class TaskGoalServicePropertiesTest {

    @Test
    void defaultsToTheTenSecondBoundedInboundTimeout() {
        TaskGoalServiceProperties properties = new TaskGoalServiceProperties();

        assertThat(properties.getInboundRequestTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.isInboundRequestTimeoutValid()).isTrue();
    }

    @Test
    void rejectsInboundTimeoutsThatTomcatCannotUse() {
        TaskGoalServiceProperties properties = new TaskGoalServiceProperties();
        properties.setInboundRequestTimeout(Duration.ofNanos(999_999));

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            assertThat(validator.validate(properties))
                    .anyMatch(violation -> violation.getPropertyPath().toString()
                            .equals("inboundRequestTimeoutValid"));
        }
    }

    @Test
    void rejectsInboundTimeoutsAboveTheServiceSafetyBound() {
        TaskGoalServiceProperties properties = new TaskGoalServiceProperties();
        properties.setInboundRequestTimeout(Duration.ofSeconds(61));

        assertThat(properties.isInboundRequestTimeoutValid()).isFalse();
    }
}
