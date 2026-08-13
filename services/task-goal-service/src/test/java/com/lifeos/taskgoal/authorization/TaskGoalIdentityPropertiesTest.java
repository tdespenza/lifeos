package com.lifeos.taskgoal.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TaskGoalIdentityPropertiesTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "http://localhost:8081",
        "http://127.0.0.1:8081",
        "http://127.42.0.1:8081",
        "http://[::1]:8081",
        "https://identity.production.example:8443"
    })
    void acceptsHttpsAndLocalLoopbackHttp(String baseUrl) {
        TaskGoalIdentityProperties properties = new TaskGoalIdentityProperties();
        properties.setBaseUrl(baseUrl);

        assertThat(properties.isBaseUrlValid()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "http://identity.production.example:8081",
        "http://10.0.0.5:8081",
        "http://192.168.1.10:8081",
        "http://127.0.0.256:8081",
        "http://999.0.0.1:8081",
        "http://127.00.0.1:8081",
        "ftp://identity.production.example",
        "/relative/identity"
    })
    void rejectsNonHttpsNonLoopbackOrNonAbsoluteUrls(String baseUrl) {
        TaskGoalIdentityProperties properties = new TaskGoalIdentityProperties();
        properties.setBaseUrl(baseUrl);

        assertThat(properties.isBaseUrlValid()).isFalse();
    }
}
