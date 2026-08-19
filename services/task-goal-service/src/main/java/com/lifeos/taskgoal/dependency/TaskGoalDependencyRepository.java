package com.lifeos.taskgoal.dependency;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Scope-bound edge access. All caller-facing operations include immutable owner/tenant scope. */
public interface TaskGoalDependencyRepository extends JpaRepository<TaskGoalDependency, UUID> {

    List<TaskGoalDependency> findAllByOwnerAccountIdAndTenantIdOrderByPredecessorTypeAscPredecessorIdAscDependentTypeAscDependentIdAsc(
            UUID ownerAccountId, String tenantId);

    boolean existsByOwnerAccountIdAndTenantIdAndPredecessorTypeAndPredecessorIdAndDependentTypeAndDependentId(
            UUID ownerAccountId,
            String tenantId,
            DependencyNodeType predecessorType,
            UUID predecessorId,
            DependencyNodeType dependentType,
            UUID dependentId);

    @Modifying
    @Query("delete from TaskGoalDependency edge "
            + "where edge.ownerAccountId = :ownerAccountId and edge.tenantId = :tenantId "
            + "and edge.predecessorType = :predecessorType and edge.predecessorId = :predecessorId "
            + "and edge.dependentType = :dependentType and edge.dependentId = :dependentId")
    int deleteByScopedEdge(
            @Param("ownerAccountId") UUID ownerAccountId,
            @Param("tenantId") String tenantId,
            @Param("predecessorType") DependencyNodeType predecessorType,
            @Param("predecessorId") UUID predecessorId,
            @Param("dependentType") DependencyNodeType dependentType,
            @Param("dependentId") UUID dependentId);
}
