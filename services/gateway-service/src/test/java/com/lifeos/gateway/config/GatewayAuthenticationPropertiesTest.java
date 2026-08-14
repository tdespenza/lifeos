package com.lifeos.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class GatewayAuthenticationPropertiesTest {

    @Test
    void acceptsHttpsAndLoopbackHttpIdentityAuthorities() {
        GatewayAuthenticationProperties properties = new GatewayAuthenticationProperties();

        properties.setBaseUrl("https://identity.production.example:8443");
        assertThat(properties.isBaseUrlValid()).isTrue();

        properties.setBaseUrl("http://127.0.0.1:8081");
        assertThat(properties.isBaseUrlValid()).isTrue();
    }

    @Test
    void rejectsCleartextRemoteAndUnboundedIdentityAuthorities() {
        GatewayAuthenticationProperties properties = new GatewayAuthenticationProperties();

        properties.setBaseUrl("http://identity.production.example:8081");
        assertThat(properties.isBaseUrlValid()).isFalse();

        properties.setBaseUrl("https://identity.production.example:8443");
        properties.setReadTimeout(Duration.ofSeconds(61));
        assertThat(properties.isTimeoutsValid()).isFalse();
    }

    @Test
    void rejectsUnsafeIdentityUrlShapesAndNonPositiveTimeouts() {
        GatewayAuthenticationProperties properties = new GatewayAuthenticationProperties();

        properties.setBaseUrl("https://user:pass@identity.test");
        assertThat(properties.isBaseUrlValid()).isFalse();
        properties.setBaseUrl("https://identity.test?token=abc");
        assertThat(properties.isBaseUrlValid()).isFalse();
        properties.setBaseUrl("https://identity.test#fragment");
        assertThat(properties.isBaseUrlValid()).isFalse();
        properties.setBaseUrl("identity.test:8443");
        assertThat(properties.isBaseUrlValid()).isFalse();
        properties.setBaseUrl("   ");
        assertThat(properties.isBaseUrlValid()).isFalse();

        properties.setBaseUrl("http://[::1]:8081");
        assertThat(properties.isBaseUrlValid()).isTrue();
        properties.setBaseUrl("http://192.0.2.10:8081");
        assertThat(properties.isBaseUrlValid()).isFalse();
        properties.setBaseUrl("http://127.0.0.01:8081");
        assertThat(properties.isBaseUrlValid()).isFalse();
        properties.setBaseUrl("http://127.0.0.256:8081");
        assertThat(properties.isBaseUrlValid()).isFalse();

        properties.setConnectTimeout(Duration.ZERO);
        assertThat(properties.isTimeoutsValid()).isFalse();
        properties.setConnectTimeout(Duration.ofSeconds(2));
        properties.setReadTimeout(Duration.ofSeconds(-1));
        assertThat(properties.isTimeoutsValid()).isFalse();
    }

    @Test
    void rejectsMissingWorkloadTokenAndInvalidValidationCapacity() {
        GatewayAuthenticationProperties properties = new GatewayAuthenticationProperties();
        properties.setBaseUrl("https://identity.test");
        properties.setMaxConcurrentValidations(0);

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertThat(validator.validate(properties))
                    .anyMatch(violation -> violation.getPropertyPath().toString().equals("workloadToken"))
                    .anyMatch(violation -> violation.getPropertyPath().toString().equals("maxConcurrentValidations"));
        }
    }
}
