package com.lifeos.trustledger.anchor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lifeos.trustledger.access.TrustSubject;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(MockitoExtension.class)
class TrustDigestAnchorServiceTest {

    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final UUID ARTIFACT_ID = UUID.randomUUID();
    private static final String PROOF = "a".repeat(64);
    private static final String DIGEST = "b".repeat(64);
    private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");

    @Mock
    private TrustDigestAnchorRequestRepository repository;

    @Mock
    private TrustAnchorClient anchorClient;

    @Mock
    private PlatformTransactionManager transactionManager;

    private TrustDigestAnchorService service;
    private TrustSubject subject;

    @BeforeEach
    void setUp() {
        service = new TrustDigestAnchorService(
                repository,
                anchorClient,
                Clock.fixed(NOW, ZoneOffset.UTC),
                transactionManager);
        subject = new TrustSubject(ACCOUNT_ID, SESSION_ID, "MEDIA_SUBJECT_PROOF", PROOF);
    }

    @Test
    void confirmsDigestOnlyAnchorAndReplaysWithoutCallingAdapterAgain() {
        TrustDigestAnchorRequest request = new TrustDigestAnchorRequest(
                ACCOUNT_ID,
                ACCOUNT_ID.toString(),
                "MEDIA_SESSION_SUMMARY",
                ARTIFACT_ID,
                3,
                DIGEST,
                "c".repeat(64),
                NOW);
        when(repository
                        .findByOwnerAccountIdAndTenantIdAndSubjectTypeAndSubjectIdAndSubjectVersionAndIdempotencyKeyHash(
                                any(), anyString(), anyString(), any(), anyLong(), anyString()))
                .thenReturn(Optional.of(request));
        when(repository.findByRequestIdAndOwnerAccountIdAndTenantId(any(), any(), any()))
                .thenReturn(Optional.of(request));
        when(anchorClient.anchorDigest(DIGEST))
                .thenReturn(new TrustAnchorClient.AnchorResult("0x" + "d".repeat(64), 42));

        TrustDigestAnchorResult first = service.anchor(subject, "MEDIA_SESSION_SUMMARY", ARTIFACT_ID, 3, DIGEST, "media-key");

        assertThat(first.state()).isEqualTo(TrustDigestAnchorState.CONFIRMED);
        assertThat(first.digestSha256()).isEqualTo(DIGEST);
        assertThat(first.blockNumber()).isEqualTo(42L);
        verify(anchorClient).anchorDigest(DIGEST);

        TrustDigestAnchorResult replay = service.anchor(subject, "MEDIA_SESSION_SUMMARY", ARTIFACT_ID, 3, DIGEST, "media-key");

        assertThat(replay.requestId()).isEqualTo(first.requestId());
        verify(anchorClient).anchorDigest(DIGEST);
    }

    @Test
    void rejectsSameKeyWithChangedDigest() {
        TrustDigestAnchorRequest request = new TrustDigestAnchorRequest(
                ACCOUNT_ID,
                ACCOUNT_ID.toString(),
                "MEDIA_SESSION_SUMMARY",
                ARTIFACT_ID,
                3,
                DIGEST,
                "c".repeat(64),
                NOW);
        when(repository
                        .findByOwnerAccountIdAndTenantIdAndSubjectTypeAndSubjectIdAndSubjectVersionAndIdempotencyKeyHash(
                                any(), anyString(), anyString(), any(), anyLong(), anyString()))
                .thenReturn(Optional.of(request));

        assertThatThrownBy(() -> service.anchor(
                        subject, "MEDIA_SESSION_SUMMARY", ARTIFACT_ID, 3, "e".repeat(64), "media-key"))
                .isInstanceOf(TrustAnchorIdempotencyConflictException.class);
        verify(anchorClient, never()).anchorDigest(anyString());
    }

    @Test
    void leavesPendingStateWhenExternalAdapterIsUnavailable() {
        TrustDigestAnchorRequest request = new TrustDigestAnchorRequest(
                ACCOUNT_ID,
                ACCOUNT_ID.toString(),
                "MEDIA_SESSION_SUMMARY",
                ARTIFACT_ID,
                3,
                DIGEST,
                "c".repeat(64),
                NOW);
        when(repository
                        .findByOwnerAccountIdAndTenantIdAndSubjectTypeAndSubjectIdAndSubjectVersionAndIdempotencyKeyHash(
                                any(), anyString(), anyString(), any(), anyLong(), anyString()))
                .thenReturn(Optional.of(request));
        when(repository.findByRequestIdAndOwnerAccountIdAndTenantId(any(), any(), any()))
                .thenReturn(Optional.of(request));
        when(anchorClient.anchorDigest(DIGEST)).thenThrow(new TrustAnchorUnavailableException());

        assertThatThrownBy(() -> service.anchor(
                        subject, "MEDIA_SESSION_SUMMARY", ARTIFACT_ID, 3, DIGEST, "media-key"))
                .isInstanceOf(TrustAnchorUnavailableException.class);
        assertThat(request.getState()).isEqualTo(TrustDigestAnchorState.PENDING_EXTERNAL_ANCHOR);
        assertThat(request.getLastFailureCode()).isEqualTo("EXTERNAL_ANCHOR_UNAVAILABLE");
    }

    @Test
    void convergesToReservationWonByConcurrentRequest() {
        TrustDigestAnchorRequest request = new TrustDigestAnchorRequest(
                ACCOUNT_ID,
                ACCOUNT_ID.toString(),
                "MEDIA_SESSION_SUMMARY",
                ARTIFACT_ID,
                3,
                DIGEST,
                "c".repeat(64),
                NOW);
        when(repository
                        .findByOwnerAccountIdAndTenantIdAndSubjectTypeAndSubjectIdAndSubjectVersionAndIdempotencyKeyHash(
                                any(), anyString(), anyString(), any(), anyLong(), anyString()))
                .thenReturn(Optional.empty(), Optional.of(request));
        when(repository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate reservation"));

        TrustDigestAnchorResult result = service.anchor(
                subject, "MEDIA_SESSION_SUMMARY", ARTIFACT_ID, 3, DIGEST, "media-key");

        assertThat(result.requestId()).isEqualTo(request.getRequestId());
        assertThat(result.state()).isEqualTo(TrustDigestAnchorState.PENDING_EXTERNAL_ANCHOR);
        verify(anchorClient, never()).anchorDigest(anyString());
    }
}
