package com.lifeos.grpc.contracts;

import com.lifeos.grpc.v1.ScopedDashboardRequest;
import com.lifeos.grpc.v1.TaskMetricsServiceGrpc;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DashboardProtoContractTest {

    @Test
    void dashboardContractIsVersionedAndGenerated() {
        ScopedDashboardRequest request = ScopedDashboardRequest.newBuilder()
                .setAccountId("00000000-0000-0000-0000-000000000001")
                .setTenantId("personal")
                .setPeriodDays(30)
                .build();

        assertEquals(30, request.getPeriodDays());
        assertNotNull(TaskMetricsServiceGrpc.getGetMetricsMethod());
    }
}
