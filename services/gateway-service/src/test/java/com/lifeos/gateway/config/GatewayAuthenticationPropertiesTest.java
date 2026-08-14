package com.lifeos.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

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
}
