package com.lifeos.trustledger.anchor;

import com.lifeos.trustledger.access.TrustSubject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import io.micrometer.core.instrument.Timer;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable digest-only anchoring for bounded non-document artifacts such as Media summaries. */
@Service
public class TrustDigestAnchorService {

    private static final int TRANSACTION_TIMEOUT_SECONDS = 5;
    private final TrustDigestAnchorRequestRepository repository;
    private final TrustAnchorClient client;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final TrustAnchorMetrics metrics;

    @Autowired
    public TrustDigestAnchorService(
            TrustDigestAnchorRequestRepository repository,
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
        transaction.setTimeout(TRANSACTION_TIMEOUT_SECONDS);
    }

    /** Compatibility constructor for isolated unit tests. */
    TrustDigestAnchorService(
            TrustDigestAnchorRequestRepository repository,
            TrustAnchorClient client,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this(repository, client, clock, transactionManager, new TrustAnchorMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
    }

    public TrustDigestAnchorResult anchor(
            TrustSubject subject,
            String subjectType,
            UUID subjectId,
            long subjectVersion,
            String digestSha256,
            String idempotencyKey) {
        Timer.Sample sample = metrics.start();
        String outcome = "failed";
        UUID claimedRequestId = null;
        try {
            validateDigest(digestSha256);
            String keyHash = hashKey(requireKey(idempotencyKey));
            Claim claim = claim(
                    subject, subjectType, subjectId, subjectVersion, digestSha256, keyHash);
            TrustDigestAnchorRequest request = claim.request();
            claimedRequestId = request.getRequestId();
            if (request.getState() == TrustDigestAnchorState.CONFIRMED
                    || !claim.shouldSubmit()) {
                outcome = request.getState().name().toLowerCase(java.util.Locale.ROOT);
                return TrustDigestAnchorResult.from(request);
            }
            TrustAnchorClient.AnchorResult submitted = client.anchorDigest(digestSha256);
            TrustDigestAnchorResult result = complete(subject, request.getRequestId(), submitted);
            outcome = result.state().name().toLowerCase(java.util.Locale.ROOT);
            return result;
        } catch (TrustAnchorIdempotencyConflictException exception) {
            outcome = "conflict";
            throw exception;
        } catch (TrustAnchorUnavailableException exception) {
            outcome = "unavailable";
            if (claimedRequestId != null) {
                reset(subject, claimedRequestId, "EXTERNAL_ANCHOR_UNAVAILABLE");
            }
            throw exception;
        } catch (RuntimeException exception) {
            if (claimedRequestId != null) {
                reset(subject, claimedRequestId, "EXTERNAL_ANCHOR_FAILED");
            }
            throw new TrustAnchorUnavailableException(exception);
        } finally {
            metrics.stop(sample, "digest", outcome);
        }
    }

    public TrustDigestAnchorResult status(TrustSubject subject, UUID requestId) {
        return repository.findForRead(requestId, subject.accountId(), subject.tenantId())
                .map(TrustDigestAnchorResult::from)
                .orElseThrow(TrustAnchorUnavailableException::new);
    }

    private Claim claim(
            TrustSubject subject,
            String subjectType,
            UUID subjectId,
            long subjectVersion,
            String digestSha256,
            String keyHash) {
        try {
            return Objects.requireNonNull(transaction.execute(status -> {
                TrustDigestAnchorRequest existing = repository
                        .findByOwnerAccountIdAndTenantIdAndSubjectTypeAndSubjectIdAndSubjectVersionAndIdempotencyKeyHash(
                                subject.accountId(), subject.tenantId(), subjectType, subjectId, subjectVersion, keyHash)
                        .orElse(null);
                if (existing == null) {
                    existing = new TrustDigestAnchorRequest(
                            subject.accountId(), subject.tenantId(), subjectType, subjectId, subjectVersion,
                            digestSha256, keyHash, clock.instant());
                    repository.saveAndFlush(existing);
                    return new Claim(existing, true);
                } else if (!existing.matches(digestSha256, subjectVersion)) {
                    throw new TrustAnchorIdempotencyConflictException();
                }
                if (existing.getState() == TrustDigestAnchorState.CONFIRMED
                        || existing.getState() == TrustDigestAnchorState.SUBMITTING) {
                    return new Claim(existing, false);
                }
                if (existing.getState() != TrustDigestAnchorState.CONFIRMED
                        && existing.getState() != TrustDigestAnchorState.SUBMITTING) {
                    existing.claim(clock.instant());
                    repository.saveAndFlush(existing);
                }
                return new Claim(existing, true);
            }));
        } catch (TrustAnchorIdempotencyConflictException exception) {
            throw exception;
        } catch (DataIntegrityViolationException exception) {
            // A concurrent first request can win the unique reservation after our
            // initial lookup. Re-read in a fresh transaction so the losing caller
            // converges to the durable winner instead of returning a spurious 503.
            return recoverAfterReservationCollision(
                    subject, subjectType, subjectId, subjectVersion, digestSha256, keyHash, exception);
        } catch (DataAccessException | TransactionTimedOutException exception) {
            throw new TrustAnchorUnavailableException(exception);
        }
    }

    private Claim recoverAfterReservationCollision(
            TrustSubject subject,
            String subjectType,
            UUID subjectId,
            long subjectVersion,
            String digestSha256,
            String keyHash,
            DataIntegrityViolationException collision) {
        try {
            return Objects.requireNonNull(transaction.execute(status -> {
                TrustDigestAnchorRequest existing = repository
                        .findByOwnerAccountIdAndTenantIdAndSubjectTypeAndSubjectIdAndSubjectVersionAndIdempotencyKeyHash(
                                subject.accountId(), subject.tenantId(), subjectType, subjectId, subjectVersion, keyHash)
                        .orElseThrow(() -> new TrustAnchorUnavailableException(collision));
                if (!existing.matches(digestSha256, subjectVersion)) {
                    throw new TrustAnchorIdempotencyConflictException();
                }
                return new Claim(existing, false);
            }));
        } catch (TrustAnchorIdempotencyConflictException exception) {
            throw exception;
        } catch (TrustAnchorUnavailableException exception) {
            throw exception;
        } catch (DataAccessException | TransactionTimedOutException exception) {
            throw new TrustAnchorUnavailableException(exception);
        }
    }

    private record Claim(TrustDigestAnchorRequest request, boolean shouldSubmit) {}

    private TrustDigestAnchorResult complete(TrustSubject subject, UUID requestId, TrustAnchorClient.AnchorResult result) {
        if (result == null || result.transactionHash() == null || result.transactionHash().isBlank()) {
            throw new TrustAnchorUnavailableException();
        }
        return Objects.requireNonNull(transaction.execute(status -> {
            TrustDigestAnchorRequest request = repository
                    .findByRequestIdAndOwnerAccountIdAndTenantId(requestId, subject.accountId(), subject.tenantId())
                    .orElseThrow(TrustAnchorUnavailableException::new);
            request.markConfirmed(result.transactionHash(), result.blockNumber(), clock.instant());
            repository.saveAndFlush(request);
            return TrustDigestAnchorResult.from(request);
        }));
    }

    private void reset(TrustSubject subject, UUID requestId, String failureCode) {
        try {
            transaction.executeWithoutResult(status -> repository
                    .findByRequestIdAndOwnerAccountIdAndTenantId(requestId, subject.accountId(), subject.tenantId())
                    .ifPresent(request -> {
                        request.resetPending(failureCode, clock.instant());
                        repository.saveAndFlush(request);
                    }));
        } catch (DataAccessException | TransactionTimedOutException ignored) {
            // The original provider classification remains the safe response; reconciliation retries by key.
        }
    }

    private static void validateDigest(String digest) {
        if (digest == null || !digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("digestSha256 must be a SHA-256 digest");
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
}
