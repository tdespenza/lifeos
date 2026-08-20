package com.lifeos.grpc.contracts;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lifeos.grpc.v1.DashboardRequestValidator;
import com.lifeos.grpc.v1.ScopedDashboardRequest;
import org.junit.jupiter.api.Test;

class DashboardRequestValidatorTest {

    @Test
    void rejectsNullRequests() {
        assertThrows(IllegalArgumentException.class, () -> DashboardRequestValidator.validate(null));
    }

    @Test
    void acceptsTheInclusivePeriodBoundaries() {
        assertDoesNotThrow(() -> DashboardRequestValidator.validate(request(1)));
        assertDoesNotThrow(() -> DashboardRequestValidator.validate(request(366)));
    }

    @Test
    void rejectsNegativeAndZeroPeriods() {
        assertThrows(IllegalArgumentException.class, () -> DashboardRequestValidator.validate(request(-1)));
        assertThrows(IllegalArgumentException.class, () -> DashboardRequestValidator.validate(request(0)));
    }

    @Test
    void rejectsPeriodsAboveTheMaximum() {
        assertThrows(IllegalArgumentException.class, () -> DashboardRequestValidator.validate(request(367)));
    }

    private static ScopedDashboardRequest request(int periodDays) {
        return ScopedDashboardRequest.newBuilder().setPeriodDays(periodDays).build();
    }
}
