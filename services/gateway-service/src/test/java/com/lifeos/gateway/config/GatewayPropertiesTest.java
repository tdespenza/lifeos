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
    void rejectsInboundTimeoutsThatTomcatCannotUse() {
        GatewayProperties properties = new GatewayProperties();
        properties.setInboundRequestTimeout(Duration.ofNanos(999_999));

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertThat(validator.validate(properties))
                    .anyMatch(violation -> violation.getPropertyPath().toString()
                            .equals("inboundRequestTimeoutValid"));
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

    @Test
    void rejectsRetryBackoffConfigurationThatIsUnboundedOrOutOfOrder() {
        GatewayProperties.Retry retry = new GatewayProperties.Retry();
        retry.setInitialBackoff(Duration.ofSeconds(2));
        retry.setMaxBackoff(Duration.ofSeconds(1));

        assertThat(retry.isBackoffValid()).isFalse();
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertThat(validator.validate(retry))
                    .anyMatch(violation -> violation.getPropertyPath().toString()
                            .equals("backoffValid"));
        }
    }

    @Test
    void rejectsRetryTotalTimeoutThatCannotContainOneFullOutboundCall() {
        GatewayProperties properties = new GatewayProperties();
        properties.setConnectTimeout(Duration.ofSeconds(2));
        properties.setReadTimeout(Duration.ofSeconds(5));
        properties.getUpstream().getRetry().setTotalTimeout(Duration.ofSeconds(6));

        assertThat(properties.isRetryTimeoutBudgetValid()).isFalse();
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertThat(validator.validate(properties))
                    .anyMatch(violation -> violation.getPropertyPath().toString()
                            .equals("retryTimeoutBudgetValid"));
        }
    }

    @Test
    void rejectsUnboundedStreamingReadLifetimes() {
        GatewayProperties.Streaming streaming = new GatewayProperties.Streaming();
        streaming.setReadLifetime(Duration.ofHours(1).plusMillis(1));

        assertThat(streaming.isTimeoutsValid()).isFalse();
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertThat(validator.validate(streaming))
                    .anyMatch(violation -> violation.getMessage().contains("one hour"));
        }
    }

    @Test
    void rejectsDocumentUploadLimitsBeyondTheReviewedMultipartBoundary() {
        GatewayProperties.DocumentUpload upload = new GatewayProperties.DocumentUpload();
        upload.setMaxRequestBodyBytes(
                GatewayProperties.DocumentUpload.MAX_DOCUMENT_UPLOAD_BODY_BYTES + 1);

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertThat(validator.validate(upload))
                    .anyMatch(violation -> violation.getPropertyPath().toString()
                            .equals("maxRequestBodyBytes"));
        }
    }

    @Test
    void rejectsMediaUploadLimitsOrTimeoutsOutsideTheReviewedBoundary() {
        GatewayProperties.MediaUpload upload = new GatewayProperties.MediaUpload();
        upload.setMaxRequestBodyBytes(
                GatewayProperties.MediaUpload.MAX_MEDIA_UPLOAD_BODY_BYTES + 1);
        upload.setReadTimeout(Duration.ofSeconds(59));

        assertThat(upload.isTimeoutsValid()).isFalse();
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertThat(validator.validate(upload))
                    .anyMatch(violation -> violation.getPropertyPath().toString()
                            .equals("maxRequestBodyBytes"));
            assertThat(validator.validate(upload))
                    .anyMatch(violation -> violation.getPropertyPath().toString()
                            .equals("timeoutsValid"));
        }
    }

    @Test
    void rejectsMediaHlsLimitsOrTimeoutsOutsideTheReviewedBoundary() {
        GatewayProperties.MediaHls hls = new GatewayProperties.MediaHls();
        hls.setMaxResponseBodyBytes(
                GatewayProperties.MediaHls.MAX_MEDIA_HLS_RESPONSE_BYTES + 1);
        hls.setConnectTimeout(Duration.ofSeconds(2));
        hls.setReadTimeout(Duration.ofSeconds(1));

        assertThat(hls.isTimeoutsValid()).isFalse();
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertThat(validator.validate(hls))
                    .anyMatch(violation -> violation.getPropertyPath().toString()
                            .equals("maxResponseBodyBytes"));
            assertThat(validator.validate(hls))
                    .anyMatch(violation -> violation.getPropertyPath().toString()
                            .equals("timeoutsValid"));
        }
    }

    @Test
    void rejectsAssistantDeadlinesThatCannotCoverConnectIdentityProviderAndMargin() {
        GatewayProperties.AiAssistant assistant = new GatewayProperties.AiAssistant();
        assistant.setConnectTimeout(Duration.ofSeconds(2));
        assistant.setReadTimeout(Duration.ofSeconds(11));

        assertThat(assistant.isTimeoutsValid()).isFalse();
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertThat(validator.validate(assistant))
                    .anyMatch(violation -> violation.getPropertyPath().toString()
                            .equals("timeoutsValid"));
        }
    }
}
