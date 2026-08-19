package com.lifeos.taskgoal.task;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

/** Scope-aware reads and bounded row locks for Task lifecycle commands. */
public interface TaskRepository extends JpaRepository<Task, UUID> {

    long countByOwnerAccountIdAndTenantId(UUID ownerAccountId, String tenantId);

    long countByOwnerAccountIdAndTenantIdAndStatus(UUID ownerAccountId, String tenantId, TaskStatus status);

    List<Task> findAllByOwnerAccountIdAndTenantIdOrderByCreatedAtAscIdAsc(UUID ownerAccountId, String tenantId);

    Optional<Task> findByIdAndOwnerAccountIdAndTenantId(UUID id, UUID ownerAccountId, String tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select task from Task task where task.id = :id")
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    Optional<Task> findByIdForUpdate(@Param("id") UUID id);
}
