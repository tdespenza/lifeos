package com.lifeos.trustledger.certificate;

import com.lifeos.trustledger.access.TrustSubject;
import com.lifeos.trustledger.anchor.TrustAnchorClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;

/** Issues deterministic, private-data-free goal certificates with durable replay semantics. */
@Service
public class TrustGoalCertificateService {

    private static final int TRANSACTION_TIMEOUT_SECONDS = 5;
    private final TrustGoalCertificateRepository repository;
    private final TaskGoalCertificateService taskGoal;
    private final TrustAnchorClient anchorClient;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public TrustGoalCertificateService(
            TrustGoalCertificateRepository repository,
            TaskGoalCertificateService taskGoal,
            TrustAnchorClient anchorClient,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.taskGoal = taskGoal;
        this.anchorClient = anchorClient;
        this.clock = clock;
        transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(TRANSACTION_TIMEOUT_SECONDS);
    }

    public TrustGoalCertificateResponse issue(TrustSubject subject, UUID goalId, String idempotencyKey) {
        String keyHash = hash(requireKey(idempotencyKey));
        Optional<TrustGoalCertificate> existing = repository.findByOwnerAccountIdAndTenantIdAndIdempotencyKeyHash(
                subject.accountId(), subject.tenantId(), keyHash);
        TrustGoalCertificate certificate;
        if (existing.isPresent()) {
            if (!existing.get().getGoalId().equals(goalId)) {
                throw new com.lifeos.trustledger.anchor.TrustAnchorIdempotencyConflictException();
            }
            certificate = existing.get();
        } else {
            TaskGoalCertificateService.GoalCertificateFacts facts = taskGoal.load(subject, goalId);
            String digest = digest(facts);
            certificate = transaction.execute(status -> {
            Optional<TrustGoalCertificate> raced = repository.findByOwnerAccountIdAndTenantIdAndIdempotencyKeyHash(
                    subject.accountId(), subject.tenantId(), keyHash);
            if (raced.isPresent()) {
                if (!raced.get().getGoalId().equals(goalId)) {
                    throw new com.lifeos.trustledger.anchor.TrustAnchorIdempotencyConflictException();
                }
                return raced.get();
            }
            try {
                return repository.saveAndFlush(new TrustGoalCertificate(
                        UUID.randomUUID(), goalId, subject.accountId(), subject.tenantId(), facts.goalVersion(),
                        facts.completedAt(), digest, keyHash, clock.instant()));
            } catch (DataIntegrityViolationException race) {
                return repository.findByOwnerAccountIdAndTenantIdAndIdempotencyKeyHash(
                                subject.accountId(), subject.tenantId(), keyHash)
                        .orElseThrow(TaskGoalCertificateUnavailableException::new);
            }
            });
        }

        if (claimAnchor(certificate.getCertificateId())) {
            try {
                TrustAnchorClient.AnchorResult result = anchorClient.anchorDigest(certificate.getAchievementDigestSha256());
                transaction.execute(status -> {
                    TrustGoalCertificate current = repository.findByCertificateId(certificate.getCertificateId())
                            .orElseThrow(TaskGoalCertificateUnavailableException::new);
                    current.confirm(result.transactionHash(), result.blockNumber(), clock.instant());
                    return repository.saveAndFlush(current);
                });
            } catch (RuntimeException ignored) {
                transaction.executeWithoutResult(status -> repository.findByCertificateId(certificate.getCertificateId())
                        .ifPresent(current -> {
                            current.resetPending(clock.instant());
                            repository.saveAndFlush(current);
                        }));
                // The durable pending reference is returned; no unavailable/failed anchor is reported as confirmed.
            }
        }
        return repository.findById(certificate.getCertificateId())
                .map(TrustGoalCertificateResponse::from)
                .orElseThrow(TaskGoalCertificateUnavailableException::new);
    }

    private boolean claimAnchor(UUID certificateId) {
        Boolean claimed = transaction.execute(status -> repository.findByCertificateId(certificateId)
                .map(current -> {
                    boolean changed = current.claimAnchor(clock.instant());
                    if (changed) {
                        repository.saveAndFlush(current);
                    }
                    return changed;
                }).orElse(false));
        return Boolean.TRUE.equals(claimed);
    }

    public TrustGoalCertificateResponse status(TrustSubject subject, UUID certificateId) {
        return repository.findByCertificateIdAndOwnerAccountIdAndTenantId(
                        certificateId, subject.accountId(), subject.tenantId())
                .map(TrustGoalCertificateResponse::from)
                .orElseThrow(TaskGoalCertificateUnavailableException::new);
    }

    public TrustGoalCertificateVerificationResponse verify(
            TrustSubject subject,
            UUID certificateId,
            TrustGoalCertificateVerificationRequest presented) {
        TrustGoalCertificate certificate = repository.findByCertificateIdAndOwnerAccountIdAndTenantId(
                        certificateId, subject.accountId(), subject.tenantId())
                .orElseThrow(TaskGoalCertificateUnavailableException::new);
        boolean factsMatch = certificate.getGoalId().equals(presented.goalId())
                && certificate.getGoalVersion() == presented.goalVersion()
                && certificate.getCompletedAt().equals(presented.completedAt())
                && certificate.getAchievementDigestSha256().equals(presented.achievementDigestSha256())
                && digest(new TaskGoalCertificateService.GoalCertificateFacts(
                        presented.goalId(), presented.goalVersion(), presented.completedAt()))
                        .equals(presented.achievementDigestSha256());
        if (!factsMatch) {
            return TrustGoalCertificateVerificationResponse.invalidResult();
        }
        return certificate.getState() == TrustGoalCertificateState.CONFIRMED
                ? TrustGoalCertificateVerificationResponse.validResult()
                : TrustGoalCertificateVerificationResponse.indeterminateResult();
    }

    static String digest(TaskGoalCertificateService.GoalCertificateFacts facts) {
        return hash("lifeos-goal-certificate-v1\n"
                + facts.goalId() + "\n"
                + facts.goalVersion() + "\n"
                + facts.completedAt().toString());
    }

    private static String requireKey(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._~-]{1,128}")) {
            throw new IllegalArgumentException("Idempotency-Key must be 1-128 ASCII-safe characters");
        }
        return value;
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
