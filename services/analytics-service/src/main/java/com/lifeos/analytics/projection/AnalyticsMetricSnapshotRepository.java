package com.lifeos.analytics.projection;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnalyticsMetricSnapshotRepository extends JpaRepository<AnalyticsMetricSnapshot, UUID> {

    Optional<AnalyticsMetricSnapshot> findByOwnerAccountIdAndTenantIdAndMetricKeyAndPeriodDays(
            UUID ownerAccountId, String tenantId, String metricKey, int periodDays);

    List<AnalyticsMetricSnapshot> findAllByOwnerAccountIdAndTenantIdAndPeriodDaysOrderByMetricKeyAsc(
            UUID ownerAccountId, String tenantId, int periodDays);

    @Modifying
    @Query("update AnalyticsMetricSnapshot snapshot set snapshot.metricValue = snapshot.metricValue + 1, "
            + "snapshot.sourceVersion = 'analytics-v1' "
            + "where snapshot.ownerAccountId = :ownerAccountId and snapshot.tenantId = :tenantId "
            + "and snapshot.metricKey = :metricKey and snapshot.periodDays = :periodDays")
    int increment(
            @Param("ownerAccountId") UUID ownerAccountId,
            @Param("tenantId") String tenantId,
            @Param("metricKey") String metricKey,
            @Param("periodDays") int periodDays);
}
