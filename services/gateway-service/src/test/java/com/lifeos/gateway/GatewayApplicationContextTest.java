package com.lifeos.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifeos.gateway.config.GatewayProperties;
import com.lifeos.gateway.routing.GatewayRouteTable;
import jakarta.validation.Validator;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class GatewayApplicationContextTest {

    @Autowired
    private GatewayRouteTable routeTable;

    @Autowired
    private GatewayProperties properties;

    @Autowired
    private Validator validator;

    @Test
    void startsWithTheDeploymentOwnedPublicRouteTable() {
        assertThat(routeTable.resolve("/api/v1/accounts")).isPresent();
        assertThat(routeTable.resolve("/api/v1/auth/login")).isPresent();
        assertThat(routeTable.resolve("/api/v1/goals")).isPresent();
        assertThat(routeTable.resolve("/api/v1/internal/authorization/decisions")).isEmpty();
    }

    @Test
    void rejectsTimeoutsLongerThanSixtySecondsDuringConfigurationValidation() {
        properties.setReadTimeout(Duration.ofSeconds(61));

        assertThat(validator.validate(properties))
                .anyMatch(violation -> violation.getMessage().contains("no greater than 60 seconds"));
    }
}
