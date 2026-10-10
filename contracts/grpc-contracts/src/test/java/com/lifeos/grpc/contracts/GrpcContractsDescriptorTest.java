package com.lifeos.grpc.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.lifeos.grpc.analytics.v1.DashboardAggregationServiceGrpc;
import com.lifeos.grpc.calendar.v1.CalendarAvailabilityServiceGrpc;
import com.lifeos.grpc.common.v1.RequestMetadata;
import com.lifeos.grpc.document.v1.DocumentRetrievalServiceGrpc;
import com.lifeos.grpc.finance.v1.FinanceSummaryServiceGrpc;
import com.lifeos.grpc.task.v1.TaskProjectionServiceGrpc;
import org.junit.jupiter.api.Test;

/** Guards the generated Java API and explicit v1 package names for all current internal contracts. */
class GrpcContractsDescriptorTest {

    @Test
    void exposesOnlyVersionedLifeosServiceDescriptors() {
        assertEquals(
                "lifeos.calendar.v1.CalendarAvailabilityService",
                CalendarAvailabilityServiceGrpc.getServiceDescriptor().getName());
        assertEquals(
                "lifeos.finance.v1.FinanceSummaryService",
                FinanceSummaryServiceGrpc.getServiceDescriptor().getName());
        assertEquals(
                "lifeos.task.v1.TaskProjectionService",
                TaskProjectionServiceGrpc.getServiceDescriptor().getName());
        assertEquals(
                "lifeos.document.v1.DocumentRetrievalService",
                DocumentRetrievalServiceGrpc.getServiceDescriptor().getName());
        assertEquals(
                "lifeos.analytics.v1.DashboardAggregationService",
                DashboardAggregationServiceGrpc.getServiceDescriptor().getName());

        assertEquals("lifeos.common.v1.RequestMetadata", RequestMetadata.getDescriptor().getFullName());
        assertNotNull(RequestMetadata.getDescriptor().findFieldByName("correlation_id"));
        assertEquals(1, RequestMetadata.getDescriptor().findFieldByName("correlation_id").getNumber());
    }
}
