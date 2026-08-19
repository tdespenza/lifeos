package com.lifeos.finance.domain;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Category history queries stay scoped to a transaction loaded through the owning service. */
public interface TransactionCategoryCorrectionRepository extends JpaRepository<TransactionCategoryCorrection, UUID> {

    Page<TransactionCategoryCorrection> findByTransactionIdOrderByCorrectedAtAscIdAsc(UUID transactionId, Pageable pageable);
}
