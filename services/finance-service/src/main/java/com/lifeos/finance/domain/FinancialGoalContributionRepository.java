package com.lifeos.finance.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Immutable contribution storage and exact integer progress aggregation. */
public interface FinancialGoalContributionRepository extends JpaRepository<FinancialGoalContribution, UUID> {

    Page<FinancialGoalContribution> findByGoalIdOrderByContributedAtAscIdAsc(UUID goalId, Pageable pageable);

    Optional<FinancialGoalContribution> findByGoalIdAndSourceTransactionId(UUID goalId, UUID sourceTransactionId);

    @Query("select contribution.amountMinor from FinancialGoalContribution contribution where contribution.goalId = :goalId")
    List<Long> findAmountsByGoalId(@Param("goalId") UUID goalId);

    @Query("select contribution.goalId as goalId, sum(contribution.amountMinor) as totalMinor "
            + "from FinancialGoalContribution contribution where contribution.goalId in :goalIds "
            + "group by contribution.goalId")
    List<FinancialGoalContributionTotal> findTotalsByGoalIds(@Param("goalIds") List<UUID> goalIds);
}
