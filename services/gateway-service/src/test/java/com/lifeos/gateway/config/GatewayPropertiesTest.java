package com.lifeos.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class GatewayPropertiesTest {

    @Test
    void rejectsPositiveSubMillisecondRateLimitWindows() {
        GatewayProperties.RateLimit rateLimit = new GatewayProperties.RateLimit();
        rateLimit.setWindow(Duration.ofNanos(999_999));

        assertThat(rateLimit.isWindowValid()).isFalse();
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertThat(validator.validate(rateLimit))
                    .anyMatch(violation -> violation.getPropertyPath().toString().equals("windowValid"));
        }
    }
}
