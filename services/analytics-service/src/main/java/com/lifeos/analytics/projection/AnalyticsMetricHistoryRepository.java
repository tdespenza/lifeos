package com.lifeos.analytics.projection;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalyticsMetricHistoryRepository extends JpaRepository<AnalyticsMetricHistory, UUID> {

    Optional<AnalyticsMetricHistory> findByOwnerAccountIdAndTenantIdAndMetricKeyAndPeriodDaysAndObservationDate(
            UUID ownerAccountId,
            String tenantId,
            String metricKey,
            int periodDays,
            LocalDate observationDate);

    List<AnalyticsMetricHistory>
            findAllByOwnerAccountIdAndTenantIdAndMetricKeyAndPeriodDaysAndObservationDateBetweenOrderByObservationDateAsc(
                    UUID ownerAccountId,
                    String tenantId,
                    String metricKey,
                    int periodDays,
                    LocalDate start,
                    LocalDate end);
}
