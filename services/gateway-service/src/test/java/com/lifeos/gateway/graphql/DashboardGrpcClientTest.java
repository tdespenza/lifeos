package com.lifeos.gateway.graphql;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class DashboardGrpcClientTest {

    @Test
    void refusesToStartWithoutDeploymentOwnedMtlsAndWorkloadCredentials() {
        DashboardGrpcProperties properties = new DashboardGrpcProperties();
        properties.setEnabled(true);
        properties.setDeadline(Duration.ofSeconds(2));

        assertThatThrownBy(() -> new DashboardGrpcClient(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mTLS files and a workload token");
    }
}
