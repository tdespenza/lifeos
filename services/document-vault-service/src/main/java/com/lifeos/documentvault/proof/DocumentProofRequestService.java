package com.lifeos.documentvault.proof;

import com.lifeos.documentvault.authorization.DocumentVaultSubject;
import com.lifeos.documentvault.config.DocumentVaultServiceProperties;
import com.lifeos.documentvault.domain.VaultDocument;
import com.lifeos.documentvault.domain.VaultDocumentRepository;
import com.lifeos.documentvault.idempotency.DocumentCommandFingerprintAccess;
import com.lifeos.documentvault.idempotency.DocumentIdempotencyConflictException;
import com.lifeos.documentvault.idempotency.DocumentIdempotencyKey;
import com.lifeos.documentvault.idempotency.DocumentIdempotencyUnavailableException;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionTemplate;

/** Reserves proof requests and their outbox envelope atomically without claiming anchoring. */
@Service
public class DocumentProofRequestService {

    private static final int TRANSACTION_TIMEOUT_SECONDS = 5;

    private final VaultDocumentRepository documentRepository;
    private final DocumentProofRequestRepository requestRepository;
    private final DocumentProofOutboxEventRepository outboxRepository;
    private final DocumentProofEventFactory eventFactory;
    private final Clock clock;
    private final String idempotencySecret;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readTransaction;

    public DocumentProofRequestService(
            VaultDocumentRepository documentRepository,
            DocumentProofRequestRepository requestRepository,
            DocumentProofOutboxEventRepository outboxRepository,
            DocumentProofEventFactory eventFactory,
            Clock documentVaultClock,
            DocumentVaultServiceProperties properties,
            PlatformTransactionManager transactionManager) {
        this.documentRepository = documentRepository;
        this.requestRepository = requestRepository;
        this.outboxRepository = outboxRepository;
        this.eventFactory = eventFactory;
        clock = documentVaultClock;
        idempotencySecret = properties.getIdempotencySecret();
        transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(TRANSACTION_TIMEOUT_SECONDS);
        readTransaction = new TransactionTemplate(transactionManager);
        readTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        readTransaction.setTimeout(TRANSACTION_TIMEOUT_SECONDS);
    }

    public DocumentProofRequestResponse request(
            DocumentVaultSubject subject, UUID documentId, String idempotencyKey) {
        String rawKey = DocumentIdempotencyKey.requireValid(idempotencyKey);
        String keyHash = com.lifeos.documentvault.idempotency.DocumentCommandFingerprintAccess.keyHash(
                rawKey, idempotencySecret);
        try {
            return Objects.requireNonNull(transaction.execute(status -> reserve(
                    subject, documentId, keyHash)));
        } catch (DocumentIdempotencyConflictException | DocumentIdempotencyUnavailableException exception) {
            throw exception;
        } catch (DataAccessException | TransactionTimedOutException exception) {
            throw new DocumentIdempotencyUnavailableException();
        }
    }

    public DocumentProofRequestResponse get(DocumentVaultSubject subject, UUID requestId) {
        try {
            return Objects.requireNonNull(readTransaction.execute(status -> requestRepository
                    .findByIdAndOwnerAccountIdAndTenantId(requestId, subject.accountId(), subject.tenantId())
                    .map(request -> DocumentProofRequestResponse.from(request, false))
                    .orElseThrow(com.lifeos.documentvault.service.DocumentResourceUnavailableException::new)));
        } catch (com.lifeos.documentvault.service.DocumentResourceUnavailableException exception) {
            throw exception;
        } catch (DataAccessException | TransactionTimedOutException exception) {
            throw new DocumentIdempotencyUnavailableException();
        }
    }

    private DocumentProofRequestResponse reserve(DocumentVaultSubject subject, UUID documentId, String keyHash) {
        VaultDocument document = documentRepository
                .findByIdAndOwnerScopeForUpdate(documentId, subject.accountId(), subject.tenantId())
                .orElseThrow(com.lifeos.documentvault.service.DocumentResourceUnavailableException::new);
        String fingerprint = DocumentCommandFingerprintAccess.proof(
                document.getId(), document.getVersion(), document.getChecksumSha256());
        Optional<DocumentProofRequest> existing = requestRepository.findByOwnerAccountIdAndTenantIdAndIdempotencyKeyHash(
                subject.accountId(), subject.tenantId(), keyHash);
        if (existing.isPresent()) {
            if (!existing.get().matches(fingerprint)) {
                throw new DocumentIdempotencyConflictException();
            }
            return DocumentProofRequestResponse.from(existing.get(), true);
        }
        DocumentProofRequest request = new DocumentProofRequest(
                document.getId(),
                subject.accountId(),
                subject.tenantId(),
                document.getVersion(),
                document.getChecksumSha256(),
                keyHash,
                fingerprint,
                clock.instant());
        try {
            requestRepository.saveAndFlush(request);
            outboxRepository.saveAndFlush(new DocumentProofOutboxEvent(
                    request, eventFactory.createPayload(request), clock.instant()));
            return DocumentProofRequestResponse.from(request, false);
        } catch (DataIntegrityViolationException exception) {
            return requestRepository
                    .findByOwnerAccountIdAndTenantIdAndIdempotencyKeyHash(
                            subject.accountId(), subject.tenantId(), keyHash)
                    .filter(candidate -> candidate.matches(fingerprint))
                    .map(candidate -> DocumentProofRequestResponse.from(candidate, true))
                    .orElseThrow(DocumentIdempotencyUnavailableException::new);
        }
    }
}
