package com.lifeos.trustledger.certificate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lifeos.trustledger.access.TrustSubject;
import com.lifeos.trustledger.anchor.TrustAnchorClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(MockitoExtension.class)
class TrustGoalCertificateServiceTest {

    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final UUID GOAL_ID = UUID.randomUUID();
    private static final String PROOF = "a".repeat(64);
    private static final Instant COMPLETED_AT = Instant.parse("2026-08-18T12:00:00Z");

    @Mock
    private TrustGoalCertificateRepository repository;

    @Mock
    private TaskGoalCertificateService taskGoal;

    @Mock
    private TrustAnchorClient anchorClient;

    @Mock
    private PlatformTransactionManager transactionManager;

    private TrustGoalCertificateService service;
    private TrustSubject subject;

    @BeforeEach
    void setUp() {
        service = new TrustGoalCertificateService(
                repository,
                taskGoal,
                anchorClient,
                Clock.fixed(Instant.parse("2026-08-18T13:00:00Z"), ZoneOffset.UTC),
                transactionManager);
        subject = new TrustSubject(ACCOUNT_ID, SESSION_ID, "password", PROOF);
    }

    @Test
    void derivesStableDigestFromOnlyGoalCompletionFactsAndLeavesPendingWhenBesuUnavailable() {
        when(repository.findByOwnerAccountIdAndTenantIdAndIdempotencyKeyHash(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(taskGoal.load(subject, GOAL_ID))
                .thenReturn(new TaskGoalCertificateService.GoalCertificateFacts(GOAL_ID, 4, COMPLETED_AT));
        TrustGoalCertificate certificate = new TrustGoalCertificate(
                UUID.randomUUID(), GOAL_ID, ACCOUNT_ID, ACCOUNT_ID.toString(), 4, COMPLETED_AT,
                TrustGoalCertificateService.digest(new TaskGoalCertificateService.GoalCertificateFacts(GOAL_ID, 4, COMPLETED_AT)),
                "b".repeat(64), Instant.parse("2026-08-18T13:00:00Z"));
        when(repository.saveAndFlush(any())).thenReturn(certificate);
        when(repository.findById(certificate.getCertificateId())).thenReturn(Optional.of(certificate));
        when(repository.findByCertificateId(certificate.getCertificateId())).thenReturn(Optional.of(certificate));
        when(anchorClient.anchorDigest(certificate.getAchievementDigestSha256()))
                .thenThrow(new com.lifeos.trustledger.anchor.TrustAnchorUnavailableException());

        TrustGoalCertificateResponse response = service.issue(subject, GOAL_ID, "certificate-key");

        assertThat(response.state()).isEqualTo(TrustGoalCertificateState.PENDING_EXTERNAL_ANCHOR);
        assertThat(response.achievementDigestSha256()).hasSize(64);
        verify(anchorClient).anchorDigest(response.achievementDigestSha256());
    }

    @Test
    void matchingReplayDoesNotReloadGoalOrCallAnchorAgain() {
        TrustGoalCertificate existing = new TrustGoalCertificate(
                UUID.randomUUID(), GOAL_ID, ACCOUNT_ID, ACCOUNT_ID.toString(), 2, COMPLETED_AT,
                "c".repeat(64), "d".repeat(64), Instant.parse("2026-08-18T13:00:00Z"));
        existing.confirm("0x" + "a".repeat(64), 12, Instant.parse("2026-08-18T13:00:00Z"));
        when(repository.findByOwnerAccountIdAndTenantIdAndIdempotencyKeyHash(any(), any(), any()))
                .thenReturn(Optional.of(existing));
        when(repository.findById(existing.getCertificateId())).thenReturn(Optional.of(existing));

        TrustGoalCertificateResponse response = service.issue(subject, GOAL_ID, "certificate-key");

        assertThat(response.certificateId()).isEqualTo(existing.getCertificateId());
        verify(taskGoal, never()).load(any(), any());
        verify(anchorClient, never()).anchorDigest(any());
    }

    @Test
    void rejectsMatchingKeyForDifferentGoal() {
        TrustGoalCertificate existing = new TrustGoalCertificate(
                UUID.randomUUID(), UUID.randomUUID(), ACCOUNT_ID, ACCOUNT_ID.toString(), 2, COMPLETED_AT,
                "c".repeat(64), "d".repeat(64), Instant.parse("2026-08-18T13:00:00Z"));
        when(repository.findByOwnerAccountIdAndTenantIdAndIdempotencyKeyHash(any(), any(), any()))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.issue(subject, GOAL_ID, "certificate-key"))
                .isInstanceOf(com.lifeos.trustledger.anchor.TrustAnchorIdempotencyConflictException.class);
    }

    @Test
    void verifiesOnlyMatchingFactsAfterAConfirmedReceipt() {
        String digest = TrustGoalCertificateService.digest(
                new TaskGoalCertificateService.GoalCertificateFacts(GOAL_ID, 2, COMPLETED_AT));
        TrustGoalCertificate existing = new TrustGoalCertificate(
                UUID.randomUUID(), GOAL_ID, ACCOUNT_ID, ACCOUNT_ID.toString(), 2, COMPLETED_AT,
                digest, "d".repeat(64), Instant.parse("2026-08-18T13:00:00Z"));
        existing.confirm("0x" + "a".repeat(64), 12, Instant.parse("2026-08-18T13:00:00Z"));
        when(repository.findByCertificateIdAndOwnerAccountIdAndTenantId(
                existing.getCertificateId(), ACCOUNT_ID, ACCOUNT_ID.toString()))
                .thenReturn(Optional.of(existing));

        var response = service.verify(
                subject,
                existing.getCertificateId(),
                new TrustGoalCertificateVerificationRequest(GOAL_ID, 2, COMPLETED_AT, digest));

        assertThat(response).isEqualTo(TrustGoalCertificateVerificationResponse.validResult());
    }
}
