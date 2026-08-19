package com.lifeos.trustledger.anchor;

import com.lifeos.trustledger.access.TrustSubject;
import com.lifeos.trustledger.proof.TrustDocumentProofRequest;
import com.lifeos.trustledger.proof.TrustDocumentProofRequestRepository;
import com.lifeos.trustledger.proof.TrustDocumentProofState;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable, owner-scoped anchor workflow with request-level idempotency and fail-closed retries. */
@Service
public class TrustAnchorService {

    private final TrustDocumentProofRequestRepository repository;
    private final TrustAnchorClient client;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final TrustAnchorMetrics metrics;

    @Autowired
    public TrustAnchorService(
            TrustDocumentProofRequestRepository repository,
            TrustAnchorClient client,
            Clock clock,
            PlatformTransactionManager transactionManager,
            TrustAnchorMetrics metrics) {
        this.repository = repository;
        this.client = client;
        this.clock = clock;
        this.metrics = metrics;
        transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(5);
    }

    public TrustAnchorResult anchor(TrustSubject subject, UUID requestId, String idempotencyKey) {
        Timer.Sample sample = metrics.start();
        String outcome = "failed";
        String keyHash = null;
        try {
            keyHash = hashKey(requireKey(idempotencyKey));
            Claim claim = claim(subject, requestId, keyHash);
            if (claim.result() != null) {
                outcome = claim.result().state().name().toLowerCase(java.util.Locale.ROOT);
                return claim.result();
            }
            TrustAnchorClient.AnchorResult submitted = client.anchor(claim.request());
            TrustAnchorResult result = complete(subject, requestId, keyHash, submitted);
            outcome = result.state().name().toLowerCase(java.util.Locale.ROOT);
            return result;
        } catch (TrustAnchorIdempotencyConflictException exception) {
            outcome = "conflict";
            throw exception;
        } catch (TrustAnchorUnavailableException exception) {
            outcome = "unavailable";
            if (keyHash != null) {
                reset(subject, requestId, keyHash, "EXTERNAL_ANCHOR_UNAVAILABLE");
            }
            throw exception;
        } catch (RuntimeException exception) {
            if (keyHash != null) {
                reset(subject, requestId, keyHash, "EXTERNAL_ANCHOR_FAILED");
            }
            throw new TrustAnchorUnavailableException(exception);
        } finally {
            metrics.stop(sample, "document", outcome);
        }
    }

    /** Returns the owner-scoped anchor state without disclosing missing or cross-owner requests. */
    public TrustAnchorResult status(TrustSubject subject, UUID requestId) {
        return repository.findForRead(
                        requestId, subject.accountId(), subject.tenantId())
                .map(TrustAnchorResult::from)
                .orElseThrow(TrustAnchorUnavailableException::new);
    }

    public TrustDocumentProofVerificationResponse verify(
            TrustSubject subject, UUID requestId, TrustDocumentProofVerificationRequest presented) {
        TrustDocumentProofRequest request = repository.findForRead(
                        requestId, subject.accountId(), subject.tenantId())
                .orElseThrow(TrustAnchorUnavailableException::new);
        boolean factsMatch = request.getDocumentId().equals(presented.documentId())
                && request.getDocumentVersion() == presented.documentVersion()
                && request.getChecksumSha256().equals(presented.checksumSha256())
                && presented.checksumSha256().matches("[0-9a-f]{64}");
        if (!factsMatch) {
            return TrustDocumentProofVerificationResponse.invalidResult();
        }
        return request.getState() == TrustDocumentProofState.CONFIRMED
                ? TrustDocumentProofVerificationResponse.validResult()
                : TrustDocumentProofVerificationResponse.indeterminateResult();
    }

    private Claim claim(TrustSubject subject, UUID requestId, String keyHash) {
        return transaction.execute(status -> {
            TrustDocumentProofRequest request = repository
                    .findByRequestIdAndOwnerAccountIdAndTenantId(requestId, subject.accountId(), subject.tenantId())
                    .orElseThrow(() -> new TrustAnchorUnavailableException(new IllegalArgumentException("proof request not found")));
            if (request.getAnchorIdempotencyKeyHash() != null
                    && !request.getAnchorIdempotencyKeyHash().equals(keyHash)) {
                throw new TrustAnchorIdempotencyConflictException();
            }
            if (request.getState() == TrustDocumentProofState.CONFIRMED
                    || request.getState() == TrustDocumentProofState.SUBMITTED) {
                return new Claim(request, TrustAnchorResult.from(request));
            }
            if (request.getState() == TrustDocumentProofState.SUBMITTING) {
                return new Claim(request, TrustAnchorResult.from(request));
            }
            request.claim(keyHash, clock.instant());
            repository.saveAndFlush(request);
            return new Claim(request, null);
        });
    }

    private TrustAnchorResult complete(
            TrustSubject subject, UUID requestId, String keyHash, TrustAnchorClient.AnchorResult anchor) {
        return transaction.execute(status -> {
            TrustDocumentProofRequest request = repository
                    .findByRequestIdAndOwnerAccountIdAndTenantId(requestId, subject.accountId(), subject.tenantId())
                    .orElseThrow(TrustAnchorUnavailableException::new);
            if (!keyHash.equals(request.getAnchorIdempotencyKeyHash())) {
                throw new TrustAnchorIdempotencyConflictException();
            }
            request.markConfirmed(anchor.transactionHash(), anchor.blockNumber(), clock.instant());
            repository.saveAndFlush(request);
            return TrustAnchorResult.from(request);
        });
    }

    private void reset(TrustSubject subject, UUID requestId, String keyHash, String code) {
        try {
            transaction.executeWithoutResult(status -> repository
                    .findByRequestIdAndOwnerAccountIdAndTenantId(requestId, subject.accountId(), subject.tenantId())
                    .filter(request -> keyHash.equals(request.getAnchorIdempotencyKeyHash()))
                    .ifPresent(request -> {
                        request.resetPending(code, clock.instant());
                        repository.saveAndFlush(request);
                    }));
        } catch (DataAccessException ignored) {
            // The original external failure is still returned; reconciliation can retry by key.
        }
    }

    private static String requireKey(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._~-]{1,128}")) {
            throw new IllegalArgumentException("Idempotency-Key must be 1-128 ASCII-safe characters");
        }
        return value;
    }

    private static String hashKey(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private record Claim(TrustDocumentProofRequest request, TrustAnchorResult result) {
    }
}
