package com.lifeos.documentvault.service;

import com.lifeos.documentvault.audit.DocumentVaultAuditEventType;
import com.lifeos.documentvault.audit.DocumentVaultSecurityAuditService;
import com.lifeos.documentvault.authorization.DocumentVaultAccessService;
import com.lifeos.documentvault.authorization.DocumentVaultAuthorizationActions;
import com.lifeos.documentvault.authorization.DocumentVaultAuthorizationDenied;
import com.lifeos.documentvault.authorization.DocumentVaultAuthorizationDependencyUnavailable;
import com.lifeos.documentvault.authorization.DocumentVaultAuthorizationResource;
import com.lifeos.documentvault.authorization.DocumentVaultSubject;
import com.lifeos.documentvault.config.DocumentVaultServiceProperties;
import com.lifeos.documentvault.domain.DocumentMetadata;
import com.lifeos.documentvault.domain.VaultDocument;
import com.lifeos.documentvault.domain.VaultDocumentRepository;
import com.lifeos.documentvault.idempotency.DocumentCommandIdempotencyService;
import com.lifeos.documentvault.idempotency.DocumentCommandOperation;
import com.lifeos.documentvault.idempotency.DocumentCommandRejectedException;
import com.lifeos.documentvault.idempotency.DocumentCommandResult;
import com.lifeos.documentvault.idempotency.DocumentIdempotencyConflictException;
import com.lifeos.documentvault.idempotency.DocumentIdempotencyUnavailableException;
import com.lifeos.documentvault.idempotency.DocumentVersionConflictException;
import com.lifeos.documentvault.storage.DocumentContentType;
import com.lifeos.documentvault.storage.DocumentObjectStore;
import com.lifeos.documentvault.storage.StagedDocumentObject;
import com.lifeos.documentvault.storage.StoredDocumentObject;
import com.lifeos.documentvault.storage.QdrantDocumentIndex;
import com.lifeos.documentvault.proof.DocumentProofRequestResponse;
import com.lifeos.documentvault.proof.DocumentProofRequestService;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Owner-scoped document metadata orchestration. File bytes remain only in the object-store
 * boundary; Postgres transactions handle references, verified content facts, and exact retries.
 */
@Service
public class DocumentVaultManagementService {

    private final VaultDocumentRepository documentRepository;
    private final DocumentObjectStore objectStore;
    private final DocumentVaultAccessService accessService;
    private final DocumentCommandIdempotencyService idempotencyService;
    private final DocumentVaultSecurityAuditService auditService;
    private final DocumentVaultMetrics metrics;
    private final DocumentVaultServiceProperties properties;
    private final Clock clock;
    private final DocumentProofRequestService proofRequestService;
    private final QdrantDocumentIndex qdrantDocumentIndex;

    public DocumentVaultManagementService(
            VaultDocumentRepository documentRepository,
            DocumentObjectStore objectStore,
            DocumentVaultAccessService accessService,
            DocumentCommandIdempotencyService idempotencyService,
            DocumentVaultSecurityAuditService auditService,
            DocumentVaultMetrics metrics,
            DocumentVaultServiceProperties properties,
            Clock documentVaultClock,
            DocumentProofRequestService proofRequestService,
            QdrantDocumentIndex qdrantDocumentIndex) {
        this.documentRepository = documentRepository;
        this.objectStore = objectStore;
        this.accessService = accessService;
        this.idempotencyService = idempotencyService;
        this.auditService = auditService;
        this.metrics = metrics;
        this.properties = properties;
        clock = documentVaultClock;
        this.proofRequestService = proofRequestService;
        this.qdrantDocumentIndex = qdrantDocumentIndex;
    }

    /**
     * Stages and verifies content before claiming the durable command. A matching retry discards
     * its fresh stage and returns the original immutable response without creating another object.
     */
    public DocumentCommandResult upload(
            DocumentVaultSubject subject,
            InputStream content,
            String rawContentType,
            DocumentMetadata metadata,
            String idempotencyKey) {
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(metadata, "metadata must not be null");
        UUID candidateDocumentId = UUID.randomUUID();
        authorize(
                subject,
                DocumentVaultAuthorizationActions.CREATE,
                DocumentVaultAuthorizationResource.forNew(candidateDocumentId, subject));
        DocumentContentType contentType = DocumentContentType.requireAllowed(rawContentType);
        StagedDocumentObject staged = objectStore.stage(
                content, contentType, properties.getMaxUploadBytes(), properties.getUploadDeadline());
        String fingerprint = com.lifeos.documentvault.idempotency.DocumentCommandFingerprintAccess.upload(
                staged.checksumSha256(), staged.contentLength(), contentType.mediaType(), metadata);
        AtomicReference<String> promotedReference = new AtomicReference<>();
        DocumentCommandResult result;
        try {
            result = idempotencyService.execute(
                    subject,
                    DocumentCommandOperation.UPLOAD,
                    candidateDocumentId,
                    -1L,
                    idempotencyKey,
                    fingerprint,
                    documentId -> completeUpload(subject, documentId, staged, metadata, promotedReference));
        } catch (DocumentCommandRejectedException | DocumentIdempotencyConflictException exception) {
            // These paths are known to have rolled back. Deleting a promoted local object is safe.
            deletePromotedQuietly(promotedReference.get());
            metrics.record("upload", "rejected");
            throw exception;
        } catch (RuntimeException exception) {
            // A database commit outcome can be indeterminate after a storage promotion. Retaining
            // an orphan is safer than deleting an object a committed metadata row may reference.
            metrics.record("upload", "unavailable");
            throw exception;
        } finally {
            discardQuietly(staged);
        }
        auditService.record(
                DocumentVaultAuditEventType.DOCUMENT_CREATED,
                subject.accountId(),
                result.replayed() ? "REPLAYED" : "CREATED");
        metrics.record("upload", result.replayed() ? "replayed" : "created");
        return result;
    }

    public DocumentView get(DocumentVaultSubject subject, UUID documentId) {
        Objects.requireNonNull(subject, "subject must not be null");
        try {
            VaultDocument document = requireOwnedDocument(subject, documentId);
            authorize(
                    subject,
                    DocumentVaultAuthorizationActions.READ,
                    DocumentVaultAuthorizationResource.fromDocument(document));
            DocumentView result = DocumentView.from(document);
            auditService.record(DocumentVaultAuditEventType.DOCUMENT_READ, subject.accountId(), "READ");
            metrics.record("read", "success");
            return result;
        } catch (DocumentResourceUnavailableException exception) {
            auditService.record(DocumentVaultAuditEventType.DOCUMENT_RESOURCE_UNAVAILABLE, subject.accountId(), "UNAVAILABLE");
            metrics.record("read", "unavailable");
            throw exception;
        }
    }

    /** Updates editable metadata behind one strong representation precondition and durable replay. */
    public DocumentCommandResult updateMetadata(
            DocumentVaultSubject subject,
            UUID documentId,
            long expectedVersion,
            DocumentMetadata metadata,
            String idempotencyKey) {
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(metadata, "metadata must not be null");
        // This caller-scoped read prevents persistent reservations for missing/cross-owner IDs.
        VaultDocument existing = requireOwnedDocument(subject, documentId);
        authorize(
                subject,
                DocumentVaultAuthorizationActions.UPDATE,
                DocumentVaultAuthorizationResource.fromDocument(existing));
        String fingerprint = com.lifeos.documentvault.idempotency.DocumentCommandFingerprintAccess.metadata(
                documentId, expectedVersion, metadata);
        DocumentCommandResult result;
        try {
            result = idempotencyService.execute(
                    subject,
                    DocumentCommandOperation.METADATA_UPDATE,
                    documentId,
                    expectedVersion,
                    idempotencyKey,
                    fingerprint,
                    ignored -> completeMetadataUpdate(subject, documentId, expectedVersion, metadata));
        } catch (DocumentResourceUnavailableException | DocumentVersionConflictException exception) {
            metrics.record("metadata_update", "rejected");
            throw exception;
        } catch (RuntimeException exception) {
            metrics.record("metadata_update", "unavailable");
            throw exception;
        }
        auditService.record(
                DocumentVaultAuditEventType.DOCUMENT_METADATA_UPDATED,
                subject.accountId(),
                result.replayed() ? "REPLAYED" : "UPDATED");
        metrics.record("metadata_update", result.replayed() ? "replayed" : "updated");
        return result;
    }

    /** Executes a bounded size-plus-one page; it deliberately omits an expensive global total. */
    public DocumentSearchPage search(DocumentVaultSubject subject, DocumentSearchQuery query) {
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(query, "query must not be null");
        authorize(
                subject,
                DocumentVaultAuthorizationActions.SEARCH,
                DocumentVaultAuthorizationResource.forCollection(subject));
        int catalogLimit = properties.getMaxSearchCatalogEntries();
        List<VaultDocument> catalog = documentRepository.findByOwnerAccountIdAndTenantIdOrderByUpdatedAtDescIdAsc(
                subject.accountId(),
                subject.tenantId(),
                PageRequest.of(0, catalogLimit + 1));
        boolean catalogTruncated = catalog.size() > catalogLimit;
        int catalogSize = Math.min(catalog.size(), catalogLimit);
        List<VaultDocument> matches = new ArrayList<>(catalogSize);
        for (int index = 0; index < catalogSize; index++) {
            VaultDocument document = catalog.get(index);
            if (matchesSearch(document, query.value())) {
                matches.add(document);
            }
        }
        matches.sort(Comparator.comparingInt((VaultDocument document) -> relevance(document, query.value()))
                .reversed()
                .thenComparing(VaultDocument::getUpdatedAt, Comparator.reverseOrder())
                .thenComparing(VaultDocument::getId));
        long requestedOffset = (long) query.page() * query.size();
        int start = requestedOffset >= matches.size() ? matches.size() : (int) requestedOffset;
        int end = Math.min(start + query.size(), matches.size());
        boolean hasNext = end < matches.size();
        List<DocumentSearchResult> results = new ArrayList<>(end - start);
        for (int index = start; index < end; index++) {
            VaultDocument document = matches.get(index);
            results.add(new DocumentSearchResult(
                    document.getId(),
                    document.getTitle(),
                    document.getSource(),
                    document.getDocumentTimestamp(),
                    document.getVersion(),
                    relevance(document, query.value())));
        }
        auditService.record(DocumentVaultAuditEventType.DOCUMENT_SEARCHED, subject.accountId(), "SEARCHED");
        metrics.record("search", "success");
        return new DocumentSearchPage(List.copyOf(results), query.page(), query.size(), hasNext, catalogTruncated);
    }

    /** Creates an owner-scoped proof request and durable outbox event for a future Trust Ledger worker. */
    public DocumentProofRequestResponse requestProof(
            DocumentVaultSubject subject, UUID documentId, String idempotencyKey) {
        VaultDocument document = requireOwnedDocument(subject, documentId);
        authorize(
                subject,
                DocumentVaultAuthorizationActions.PROOF_REQUEST,
                DocumentVaultAuthorizationResource.fromDocument(document));
        return proofRequestService.request(subject, documentId, idempotencyKey);
    }

    public DocumentProofRequestResponse getProofRequest(DocumentVaultSubject subject, UUID requestId) {
        DocumentProofRequestResponse result = proofRequestService.get(subject, requestId);
        VaultDocument document = requireOwnedDocument(subject, result.documentId());
        authorize(
                subject,
                DocumentVaultAuthorizationActions.READ,
                DocumentVaultAuthorizationResource.fromDocument(document));
        return result;
    }

    private DocumentView completeUpload(
            DocumentVaultSubject subject,
            UUID documentId,
            StagedDocumentObject staged,
            DocumentMetadata metadata,
            AtomicReference<String> promotedReference) {
        VaultDocument existing = documentRepository
                .findByIdAndOwnerScopeForUpdate(documentId, subject.accountId(), subject.tenantId())
                .orElse(null);
        if (existing != null) {
            if (matchesUpload(existing, staged, metadata)) {
                qdrantDocumentIndex.index(existing, staged.searchableText());
                return DocumentView.from(existing);
            }
            throw new DocumentIdempotencyUnavailableException();
        }
        StoredDocumentObject stored = objectStore.promote(staged, documentId);
        promotedReference.set(stored.objectReference());
        VaultDocument document = new VaultDocument(
                documentId,
                subject.accountId(),
                subject.tenantId(),
                stored.objectReference(),
                staged.checksumSha256(),
                staged.contentLength(),
                staged.contentType().mediaType(),
                metadata,
                DocumentSearchTokenHasher.encode(properties.getIdempotencySecret(), staged.searchableText()),
                clock.instant());
        documentRepository.saveAndFlush(document);
        qdrantDocumentIndex.index(document, staged.searchableText());
        return DocumentView.from(document);
    }

    private DocumentView completeMetadataUpdate(
            DocumentVaultSubject subject, UUID documentId, long expectedVersion, DocumentMetadata metadata) {
        VaultDocument document = documentRepository
                .findByIdAndOwnerScopeForUpdate(documentId, subject.accountId(), subject.tenantId())
                .orElseThrow(DocumentResourceUnavailableException::new);
        if (document.getVersion() == expectedVersion) {
            document.updateMetadata(metadata, clock.instant());
            documentRepository.saveAndFlush(document);
            return DocumentView.from(document);
        }
        // A crash after the resource flush but before reservation completion is recoverable if the
        // exact requested representation is already present at the expected next version.
        if (document.getVersion() == expectedVersion + 1 && matchesMetadata(document, metadata)) {
            return DocumentView.from(document);
        }
        throw new DocumentVersionConflictException();
    }

    private VaultDocument requireOwnedDocument(DocumentVaultSubject subject, UUID documentId) {
        if (documentId == null) {
            throw new DocumentResourceUnavailableException();
        }
        return documentRepository
                .findByIdAndOwnerAccountIdAndTenantId(documentId, subject.accountId(), subject.tenantId())
                .orElseThrow(DocumentResourceUnavailableException::new);
    }

    private static boolean matchesUpload(VaultDocument document, StagedDocumentObject staged, DocumentMetadata metadata) {
        return document.getChecksumSha256().equals(staged.checksumSha256())
                && document.getContentLength() == staged.contentLength()
                && document.getContentType().equals(staged.contentType().mediaType())
                && matchesMetadata(document, metadata);
    }

    private static boolean matchesMetadata(VaultDocument document, DocumentMetadata metadata) {
        return document.getTitle().equals(metadata.title())
                && document.getTags().equals(metadata.tags())
                && Objects.equals(document.getDocumentTimestamp(), metadata.documentTimestamp())
                && document.getSource() == metadata.source()
                && document.getClassification() == metadata.classification();
    }

    private int relevance(VaultDocument document, String query) {
        String title = document.getTitle().toLowerCase(Locale.ROOT);
        if (title.equals(query)) {
            return 100;
        }
        if (title.startsWith(query)) {
            return 80;
        }
        if (title.contains(query)) {
            return 60;
        }
        if (document.getTags().stream().anyMatch(tag -> tag.contains(query))) {
            return 40;
        }
        return DocumentSearchTokenHasher.containsAny(
                        properties.getIdempotencySecret(), document.getContentSearchTokenDigests(), query)
                ? 30
                : 20;
    }

    private boolean matchesSearch(VaultDocument document, String query) {
        return document.getTitle().toLowerCase(Locale.ROOT).contains(query)
                || document.getTags().stream().anyMatch(tag -> tag.contains(query))
                || DocumentSearchTokenHasher.containsAny(
                        properties.getIdempotencySecret(), document.getContentSearchTokenDigests(), query);
    }

    private void authorize(
            DocumentVaultSubject subject, String action, DocumentVaultAuthorizationResource resource) {
        try {
            accessService.authorize(subject, action, resource);
            auditService.record(DocumentVaultAuditEventType.AUTHORIZATION_ALLOWED, subject.accountId(), "ALLOWED");
        } catch (DocumentVaultAuthorizationDenied exception) {
            auditService.record(DocumentVaultAuditEventType.AUTHORIZATION_DENIED, subject.accountId(), "DENIED");
            throw exception;
        } catch (DocumentVaultAuthorizationDependencyUnavailable exception) {
            auditService.record(
                    DocumentVaultAuditEventType.AUTHORIZATION_DEPENDENCY_UNAVAILABLE, subject.accountId(), "UNAVAILABLE");
            throw exception;
        }
    }

    private void discardQuietly(StagedDocumentObject staged) {
        try {
            objectStore.discard(staged);
        } catch (RuntimeException exception) {
            metrics.record("storage_cleanup", "unavailable");
        }
    }

    private void deletePromotedQuietly(String objectReference) {
        if (objectReference == null) {
            return;
        }
        try {
            objectStore.delete(objectReference);
        } catch (RuntimeException exception) {
            metrics.record("storage_cleanup", "unavailable");
        }
    }
}
