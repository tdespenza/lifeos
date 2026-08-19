package com.lifeos.identity.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Verifies that identity-service inbound timeout configuration fails before startup when unsafe. */
class IdentityServicePropertiesTest {

    @Test
    void rejectsInboundTimeoutsThatTomcatCannotUse() {
        IdentityServiceProperties properties = new IdentityServiceProperties();
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
        IdentityServiceProperties properties = new IdentityServiceProperties();
        properties.setInboundRequestTimeout(Duration.ofSeconds(61));

        assertThat(properties.isInboundRequestTimeoutValid()).isFalse();
    }
}
