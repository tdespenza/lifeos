package com.lifeos.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifeos.gateway.routing.GatewayRouteTable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class GatewayApplicationContextTest {

    @Autowired
    private GatewayRouteTable routeTable;

    @Test
    void startsWithTheDeploymentOwnedPublicRouteTable() {
        assertThat(routeTable.resolve("/api/v1/accounts")).isPresent();
        assertThat(routeTable.resolve("/api/v1/auth/login")).isPresent();
        assertThat(routeTable.resolve("/api/v1/goals")).isPresent();
        assertThat(routeTable.resolve("/api/v1/internal/authorization/decisions")).isEmpty();
    }
}
