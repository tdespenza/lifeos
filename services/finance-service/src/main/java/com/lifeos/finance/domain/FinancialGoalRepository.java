package com.lifeos.finance.domain;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

/** Owner/tenant scoped financial goal persistence. */
public interface FinancialGoalRepository extends JpaRepository<FinancialGoal, UUID> {

    Page<FinancialGoal> findByOwnerAccountIdAndTenantIdOrderByCreatedAtDescIdDesc(
            UUID ownerAccountId, String tenantId, Pageable pageable);

    Optional<FinancialGoal> findByIdAndOwnerAccountIdAndTenantId(UUID id, UUID ownerAccountId, String tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select goal from FinancialGoal goal where goal.id = :id and goal.ownerAccountId = :ownerAccountId "
            + "and goal.tenantId = :tenantId")
    Optional<FinancialGoal> findOwnedForUpdate(
            @Param("id") UUID id,
            @Param("ownerAccountId") UUID ownerAccountId,
            @Param("tenantId") String tenantId);
}
