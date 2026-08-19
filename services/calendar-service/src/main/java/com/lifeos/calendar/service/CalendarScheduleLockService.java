package com.lifeos.calendar.service;

import com.lifeos.calendar.authorization.CalendarSubject;
import com.lifeos.calendar.domain.CalendarScheduleLock;
import com.lifeos.calendar.domain.CalendarScheduleLockRepository;
import java.time.Clock;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates a durable owner guard outside the schedule-write transaction, then locks that guard in
 * the caller's transaction. The two-step shape avoids leaving a transaction rollback-only when
 * two first-ever writes race to create the same primary-key row.
 */
@Service
public class CalendarScheduleLockService {

    private final CalendarScheduleLockRepository repository;
    private final Clock clock;
    private final TransactionTemplate creationTransaction;

    public CalendarScheduleLockService(
            CalendarScheduleLockRepository repository, Clock clock, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.clock = clock;
        creationTransaction = new TransactionTemplate(transactionManager);
        creationTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        creationTransaction.setTimeout(5);
    }

    /**
     * Serializes conflicting schedule writes for one personal tenant. The caller must already be
     * in the short transaction that performs conflict detection and persistence.
     */
    public void acquire(CalendarSubject subject) {
        ensureExists(subject);
        CalendarScheduleLock lock = repository.findByOwnerAccountIdForUpdate(subject.accountId())
                .orElseThrow(() -> new IllegalStateException("calendar schedule guard was not persisted"));
        requireTenant(lock, subject);
    }

    private void ensureExists(CalendarSubject subject) {
        try {
            creationTransaction.executeWithoutResult(status -> {
                CalendarScheduleLock existing = repository.findById(subject.accountId()).orElse(null);
                if (existing != null) {
                    requireTenant(existing, subject);
                    return;
                }
                repository.saveAndFlush(CalendarScheduleLock.forOwner(
                        subject.accountId(), subject.tenantId(), clock.instant()));
            });
        } catch (DataIntegrityViolationException exception) {
            // A separate transaction has won the primary-key race. It committed before this
            // catch executes, so the caller can now lock the durable winning row below.
            CalendarScheduleLock raced = repository.findById(subject.accountId()).orElseThrow(() -> exception);
            requireTenant(raced, subject);
        }
    }

    private static void requireTenant(CalendarScheduleLock lock, CalendarSubject subject) {
        if (!subject.tenantId().equals(lock.getTenantId())) {
            throw new IllegalStateException("calendar schedule guard tenancy mismatch");
        }
    }
}
