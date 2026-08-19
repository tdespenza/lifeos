package com.lifeos.taskgoal.dependency;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

/** Durable per-graph mutation lock lookup. */
public interface TaskGoalDependencyGuardRepository extends JpaRepository<TaskGoalDependencyGuard, UUID> {

    Optional<TaskGoalDependencyGuard> findByOwnerAccountIdAndTenantId(UUID ownerAccountId, String tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select guard from TaskGoalDependencyGuard guard "
            + "where guard.id = :id and guard.ownerAccountId = :ownerAccountId and guard.tenantId = :tenantId")
    Optional<TaskGoalDependencyGuard> findByIdAndScopeForUpdate(
            @Param("id") UUID id,
            @Param("ownerAccountId") UUID ownerAccountId,
            @Param("tenantId") String tenantId);
}
