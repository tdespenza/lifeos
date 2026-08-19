package com.lifeos.identity.notification;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Atomically enqueues privacy-safe recovery security notifications with the recovery mutation. */
@Service
public class IdentityRecoveryNotificationService {

    private final IdentityNotificationOutboxEventRepository repository;
    private final IdentityRecoveryNotificationFactory factory;

    public IdentityRecoveryNotificationService(
            IdentityNotificationOutboxEventRepository repository,
            IdentityRecoveryNotificationFactory factory) {
        this.repository = repository;
        this.factory = factory;
    }

    @Transactional
    public void enqueueCodesIssued(UUID accountId) {
        repository.save(factory.recoveryCodesIssued(accountId));
    }

    @Transactional
    public void enqueueRecoverySucceeded(UUID accountId) {
        repository.save(factory.recoverySucceeded(accountId));
    }
}
