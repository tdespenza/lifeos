package com.lifeos.grpc.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.lifeos.grpc.common.v1.RequestMetadata;
import com.lifeos.grpc.v1.ScopedDashboardRequest;
import com.lifeos.grpc.v1.TaskMetricsServiceGrpc;
import org.junit.jupiter.api.Test;

class DashboardProtoContractTest {

    @Test
    void dashboardContractIsVersionedAndGenerated() {
        ScopedDashboardRequest request = ScopedDashboardRequest.newBuilder()
                .setMetadata(RequestMetadata.newBuilder()
                        .setCorrelationId("correlation-1")
                        .build())
                .setAccountId("00000000-0000-0000-0000-000000000001")
                .setTenantId("personal")
                .setPeriodDays(30)
                .build();

        assertEquals(30, request.getPeriodDays());
        assertEquals("correlation-1", request.getMetadata().getCorrelationId());
        assertNotNull(TaskMetricsServiceGrpc.getGetMetricsMethod());
    }
}
