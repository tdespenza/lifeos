package com.lifeos.trustledger.certificate;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

public interface TrustGoalCertificateRepository extends JpaRepository<TrustGoalCertificate, UUID> {

    Optional<TrustGoalCertificate> findByOwnerAccountIdAndTenantIdAndIdempotencyKeyHash(
            UUID ownerAccountId, String tenantId, String idempotencyKeyHash);

    Optional<TrustGoalCertificate> findByCertificateIdAndOwnerAccountIdAndTenantId(
            UUID certificateId, UUID ownerAccountId, String tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<TrustGoalCertificate> findByCertificateId(UUID certificateId);
}
