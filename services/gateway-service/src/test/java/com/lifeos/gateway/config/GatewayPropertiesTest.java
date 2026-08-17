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

    @Test
    void rejectsInvalidRequestBodyBufferCapacity() {
        GatewayProperties properties = new GatewayProperties();
        properties.setMaxConcurrentRequestBodyBuffers(0);

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertThat(validator.validate(properties))
                    .anyMatch(violation -> violation.getPropertyPath().toString()
                            .equals("maxConcurrentRequestBodyBuffers"));
        }
    }

    @Test
    void rejectsInvalidResponseBufferCapacity() {
        GatewayProperties properties = new GatewayProperties();
        properties.setMaxConcurrentResponseBuffers(0);

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertThat(validator.validate(properties))
                    .anyMatch(violation -> violation.getPropertyPath().toString()
                            .equals("maxConcurrentResponseBuffers"));
        }
    }

    @Test
    void rejectsRequestBufferConfigurationThatExceedsItsAggregateBudget() {
        GatewayProperties properties = new GatewayProperties();
        properties.setMaxConcurrentRequestBodyBuffers(2);
        properties.setMaxRequestBodyBytes(4);
        properties.setMaxRequestBodyBufferBytes(7);

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertThat(validator.validate(properties))
                    .anyMatch(violation -> violation.getPropertyPath().toString()
                            .equals("requestBodyBufferBudgetValid"));
        }
    }

    @Test
    void rejectsResponseBufferConfigurationThatExceedsItsAggregateBudget() {
        GatewayProperties properties = new GatewayProperties();
        properties.setMaxConcurrentResponseBuffers(2);
        properties.setMaxResponseBodyBytes(4);
        properties.setMaxResponseBufferBytes(7);

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertThat(validator.validate(properties))
                    .anyMatch(violation -> violation.getPropertyPath().toString()
                            .equals("responseBufferBudgetValid"));
        }
    }

    @Test
    void rejectsPreAuthenticationBudgetBelowTheAccountBudget() {
        GatewayProperties.RateLimit rateLimit = new GatewayProperties.RateLimit();
        rateLimit.setMaxRequests(600);
        rateLimit.setPreAuthenticationMaxRequests(599);

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertThat(validator.validate(rateLimit))
                    .anyMatch(violation -> violation.getPropertyPath().toString()
                            .equals("preAuthenticationLimitValid"));
        }
    }
}
