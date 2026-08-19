package com.lifeos.finance.domain;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

/** Owner-scoped budget access plus a portable overlap precheck before PostgreSQL's final invariant. */
public interface FinanceBudgetRepository extends JpaRepository<FinanceBudget, UUID> {

    long countByOwnerAccountIdAndTenantId(UUID ownerAccountId, String tenantId);

    Page<FinanceBudget> findByOwnerAccountIdAndTenantIdOrderByPeriodStartDescIdDesc(
            UUID ownerAccountId, String tenantId, Pageable pageable);

    Optional<FinanceBudget> findByIdAndOwnerAccountIdAndTenantId(UUID id, UUID ownerAccountId, String tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select budget from FinanceBudget budget where budget.id = :id "
            + "and budget.ownerAccountId = :ownerAccountId and budget.tenantId = :tenantId")
    Optional<FinanceBudget> findOwnedForUpdate(
            @Param("id") UUID id,
            @Param("ownerAccountId") UUID ownerAccountId,
            @Param("tenantId") String tenantId);

    @Query("select budget from FinanceBudget budget where budget.ownerAccountId = :ownerAccountId "
            + "and budget.tenantId = :tenantId and budget.category = :category "
            + "and budget.periodStart <= :periodEnd and budget.periodEnd >= :periodStart "
            + "and (:excludedId is null or budget.id <> :excludedId)")
    List<FinanceBudget> findOverlaps(
            @Param("ownerAccountId") UUID ownerAccountId,
            @Param("tenantId") String tenantId,
            @Param("category") String category,
            @Param("periodStart") LocalDate periodStart,
            @Param("periodEnd") LocalDate periodEnd,
            @Param("excludedId") UUID excludedId);
}
