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

/** Owner/tenant scoped posting access. Every query retains the local self-only boundary. */
public interface FinancialTransactionRepository extends JpaRepository<FinancialTransaction, UUID> {

    long countByOwnerAccountIdAndTenantId(UUID ownerAccountId, String tenantId);

    Optional<FinancialTransaction> findByIdAndOwnerAccountIdAndTenantId(UUID id, UUID ownerAccountId, String tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select transaction from FinancialTransaction transaction where transaction.id = :id "
            + "and transaction.ownerAccountId = :ownerAccountId and transaction.tenantId = :tenantId")
    Optional<FinancialTransaction> findOwnedForUpdate(
            @Param("id") UUID id,
            @Param("ownerAccountId") UUID ownerAccountId,
            @Param("tenantId") String tenantId);

    Page<FinancialTransaction> findByOwnerAccountIdAndTenantIdOrderByOccurredOnDescIdDesc(
            UUID ownerAccountId, String tenantId, Pageable pageable);

    List<FinancialTransaction> findByOwnerAccountIdAndTenantIdAndCurrencyAndOccurredOnBetweenOrderByOccurredOnAscIdAsc(
            UUID ownerAccountId, String tenantId, String currency, LocalDate from, LocalDate to, Pageable pageable);

    boolean existsByOwnerAccountIdAndTenantIdAndOccurredOnBetweenAndCurrencyNot(
            UUID ownerAccountId, String tenantId, LocalDate from, LocalDate to, String currency);

    Optional<FinancialTransaction> findByIdAndOwnerAccountIdAndTenantIdAndCurrency(
            UUID id, UUID ownerAccountId, String tenantId, String currency);
}
