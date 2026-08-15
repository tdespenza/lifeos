package com.lifeos.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifeos.gateway.config.GatewayProperties;
import com.lifeos.gateway.routing.GatewayRouteTable;
import jakarta.validation.Validator;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "gateway.authentication.workload-token=test-only-gateway-workload-token")
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
        assertThat(routeTable.resolve("/api/v1/auth/sessions")).get()
                .satisfies(route -> assertThat(route.requiresAuthentication("GET")).isTrue());
        assertThat(routeTable.resolve("/api/v1/goals")).isPresent();
        assertThat(routeTable.resolve("/api/v1/goals")).get()
                .extracting(route -> route.authenticationRequired())
                .isEqualTo(true);
        assertThat(routeTable.resolve("/api/v1/accounts/00000000-0000-4000-8000-000000000001")).get()
                .satisfies(route -> {
                    assertThat(route.requiresAuthentication("/api/v1/accounts", "POST")).isFalse();
                    assertThat(route.requiresAuthentication(
                            "/api/v1/accounts/00000000-0000-4000-8000-000000000001", "POST")).isTrue();
                    assertThat(route.requiresAuthentication(
                            "/api/v1/accounts/00000000-0000-4000-8000-000000000001", "PUT")).isTrue();
                    assertThat(route.requiresAuthentication(
                            "/api/v1/accounts/00000000-0000-4000-8000-000000000001", "PATCH")).isTrue();
                    assertThat(route.requiresAuthentication(
                            "/api/v1/accounts/00000000-0000-4000-8000-000000000001", "DELETE")).isTrue();
                });
        assertThat(routeTable.resolve("/api/v1/internal/authorization/decisions")).isEmpty();
    }

    @Test
    void rejectsTimeoutsLongerThanSixtySecondsDuringConfigurationValidation() {
        properties.setReadTimeout(Duration.ofSeconds(61));

        assertThat(validator.validate(properties))
                .anyMatch(violation -> violation.getMessage().contains("no greater than 60 seconds"));
    }
}
