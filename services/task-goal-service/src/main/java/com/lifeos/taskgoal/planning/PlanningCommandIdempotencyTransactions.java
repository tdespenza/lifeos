package com.lifeos.taskgoal.planning;

import com.lifeos.taskgoal.authorization.TaskSubject;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Isolates planning idempotency reservation failures from the command transaction. */
@Service
public class PlanningCommandIdempotencyTransactions {

    public static final int TRANSACTION_TIMEOUT_SECONDS = 5;

    private final PlanningCommandIdempotencyRepository repository;

    public PlanningCommandIdempotencyTransactions(PlanningCommandIdempotencyRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = TRANSACTION_TIMEOUT_SECONDS)
    public PlanningCommandIdempotency reserve(
            TaskSubject subject,
            String operation,
            UUID resourceId,
            String keyHash,
            String fingerprint) {
        return repository.saveAndFlush(new PlanningCommandIdempotency(
                subject.accountId(), subject.tenantId(), operation, keyHash, fingerprint, resourceId));
    }

    @Transactional(readOnly = true, timeout = TRANSACTION_TIMEOUT_SECONDS)
    public Optional<PlanningCommandIdempotency> findExisting(
            TaskSubject subject, String operation, String keyHash) {
        return repository.findByOwnerAccountIdAndTenantIdAndOperationAndKeyHash(
                subject.accountId(), subject.tenantId(), operation, keyHash);
    }
}
