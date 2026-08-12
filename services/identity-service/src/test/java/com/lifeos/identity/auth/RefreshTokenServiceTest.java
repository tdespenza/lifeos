package com.lifeos.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lifeos.identity.account.UserAccount;
import com.lifeos.identity.account.UserAccountRepository;
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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-12T00:00:00Z");

    @Mock
    private TokenFamilyRepository familyRepository;
    @Mock
    private ConsumedRefreshTokenRepository consumedRepository;
    @Mock
    private RefreshReplayRecordRepository replayRepository;
    @Mock
    private AuthSessionRepository sessionRepository;
    @Mock
    private UserAccountRepository accountRepository;
    @Mock
    private JwtEncoder jwtEncoder;
    @Mock
    private OpaqueTokenGenerator tokenGenerator;
    @Mock
    private RefreshResponseCipher responseCipher;

    private IdentityAuthProperties properties;
    private RefreshTokenService service;
    private UserAccount account;
    private AuthSession session;
    private TokenFamily family;

    @BeforeEach
    void setUp() {
        properties = new IdentityAuthProperties();
        properties.getJwt().setSigningSecret("test-only-secret-that-is-at-least-32-bytes-long");
        service = new RefreshTokenService(
                familyRepository, consumedRepository, replayRepository, sessionRepository,
                accountRepository, jwtEncoder, tokenGenerator, responseCipher, properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
        account = new UserAccount("ada@example.com", "Ada Lovelace");
        ReflectionTestUtils.setField(account, "id", UUID.randomUUID());
        session = new AuthSession(
                UUID.randomUUID(), account, SessionAuthenticationMethod.PASSWORD,
                TokenDigest.sha256("access"), NOW.minusSeconds(300), NOW.minusSeconds(1));
        family = new TokenFamily(
                UUID.randomUUID(), account.getId(), session.getId(), TokenDigest.sha256("presented"),
                NOW, NOW.plus(properties.getJwt().getRefreshTokenTtl()),
                NOW.plus(properties.getJwt().getRefreshFamilyTtl()),
                NOW.plus(properties.getJwt().getRefreshIdleTtl()));
    }

    @Test
    void rotatesPredecessorExactlyOnceAndStoresOnlyDigests() {
        when(familyRepository.findByActiveTokenHash(TokenDigest.sha256("presented")))
                .thenReturn(Optional.of(family));
        when(familyRepository.findByIdForUpdate(family.getId())).thenReturn(Optional.of(family));
        when(replayRepository.findByFamilyIdAndIdempotencyKeyForUpdate(family.getId(), "key"))
                .thenReturn(Optional.empty());
        when(consumedRepository.countByFamilyId(family.getId())).thenReturn(0L);
        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        when(tokenGenerator.next()).thenReturn("successor-refresh");
        when(jwtEncoder.encode(any())).thenReturn(Jwt.withTokenValue("successor-access")
                .header("alg", "HS256")
                .claim("sub", account.getId().toString())
                .issuedAt(NOW)
                .expiresAt(NOW.plusSeconds(300))
                .build());
        when(responseCipher.encrypt(any(), any(), any())).thenReturn("encrypted-envelope");

        LoginResponse response = service.refresh(new RefreshTokenService.RefreshRequest(
                "presented", "key", "fingerprint"));

        assertThat(response.accessToken()).isEqualTo("successor-access");
        assertThat(response.refreshToken()).isEqualTo("successor-refresh");
        assertThat(family.getActiveTokenHash()).isEqualTo(TokenDigest.sha256("successor-refresh"));
        assertThat(session.getAccessTokenHash()).isEqualTo(TokenDigest.sha256("successor-access"));
        assertThat(session.getExpiresAt()).isEqualTo(NOW.plusSeconds(300));
        verify(consumedRepository).save(any(ConsumedRefreshToken.class));
        verify(replayRepository).save(any(RefreshReplayRecord.class));
    }

    @Test
    void matchingCommittedRetryReturnsTheSameSuccessorOnce() {
        RefreshReplayRecord replay = new RefreshReplayRecord(
                family.getId(), "key", "fingerprint", TokenDigest.sha256("presented"),
                NOW.plusSeconds(30));
        replay.commit("encrypted-envelope");
        LoginResponse expected = new LoginResponse(
                session.getId(), "successor-access", "Bearer", 300,
                "successor-refresh", 100);
        when(familyRepository.findByActiveTokenHash(TokenDigest.sha256("presented")))
                .thenReturn(Optional.of(family));
        when(familyRepository.findByIdForUpdate(family.getId())).thenReturn(Optional.of(family));
        when(replayRepository.findByFamilyIdAndIdempotencyKeyForUpdate(family.getId(), "key"))
                .thenReturn(Optional.of(replay));
        when(responseCipher.decrypt(family.getId(), "key", "encrypted-envelope"))
                .thenReturn(expected);

        LoginResponse actual = service.refresh(new RefreshTokenService.RefreshRequest(
                "presented", "key", "fingerprint"));

        assertThat(actual).isEqualTo(expected);
        assertThat(replay.getRetryCount()).isEqualTo(1);
        verify(consumedRepository, never()).save(any());

        assertThatThrownBy(() -> service.refresh(new RefreshTokenService.RefreshRequest(
                "presented", "key", "fingerprint")))
                .isInstanceOf(RefreshTokenService.FamilyStateChangedException.class);
        assertThat(family.getStatus()).isEqualTo(TokenFamilyStatus.REVOKED);
    }

    @Test
    void rejectsConsumedTokenWhenCommittedReplayBelongsToAnotherPredecessor() {
        RefreshReplayRecord replay = new RefreshReplayRecord(
                family.getId(), "key", "fingerprint", TokenDigest.sha256("presented"),
                NOW.plusSeconds(30));
        replay.commit("encrypted-envelope");
        when(familyRepository.findByActiveTokenHash(TokenDigest.sha256("consumed")))
                .thenReturn(Optional.empty());
        when(consumedRepository.findByTokenHash(TokenDigest.sha256("consumed")))
                .thenReturn(Optional.of(new ConsumedRefreshToken(
                        family.getId(), TokenDigest.sha256("consumed"), NOW.minusSeconds(1))));
        when(familyRepository.findByIdForUpdate(family.getId())).thenReturn(Optional.of(family));
        when(replayRepository.findByFamilyIdAndIdempotencyKeyForUpdate(family.getId(), "key"))
                .thenReturn(Optional.of(replay));

        assertThatThrownBy(() -> service.refresh(new RefreshTokenService.RefreshRequest(
                "consumed", "key", "fingerprint")))
                .isInstanceOf(RefreshTokenService.FamilyStateChangedException.class);
        assertThat(family.getStatus()).isEqualTo(TokenFamilyStatus.REVOKED);
    }

    @Test
    void expiresFamilyWhenItsRefreshDeadlineHasPassed() {
        TokenFamily expired = new TokenFamily(
                family.getId(), account.getId(), session.getId(),
                TokenDigest.sha256("presented"), NOW.minusSeconds(100),
                NOW.minusSeconds(1), NOW.plusSeconds(100), NOW.plusSeconds(100));
        when(familyRepository.findByActiveTokenHash(TokenDigest.sha256("presented")))
                .thenReturn(Optional.of(expired));
        when(familyRepository.findByIdForUpdate(expired.getId())).thenReturn(Optional.of(expired));
        when(replayRepository.findByFamilyIdAndIdempotencyKeyForUpdate(expired.getId(), "key"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.refresh(new RefreshTokenService.RefreshRequest(
                "presented", "key", "fingerprint")))
                .isInstanceOf(RefreshTokenService.FamilyStateChangedException.class);
        assertThat(expired.getStatus()).isEqualTo(TokenFamilyStatus.EXPIRED);
    }

    @Test
    void revokesFamilyWhenReplayEvidenceBoundIsReached() {
        properties.getJwt().setMaxRefreshReplayRecordsPerFamily(1);
        when(familyRepository.findByActiveTokenHash(TokenDigest.sha256("presented")))
                .thenReturn(Optional.of(family));
        when(familyRepository.findByIdForUpdate(family.getId())).thenReturn(Optional.of(family));
        when(replayRepository.findByFamilyIdAndIdempotencyKeyForUpdate(family.getId(), "key"))
                .thenReturn(Optional.empty());
        when(consumedRepository.countByFamilyId(family.getId())).thenReturn(1L);

        assertThatThrownBy(() -> service.refresh(new RefreshTokenService.RefreshRequest(
                "presented", "key", "fingerprint")))
                .isInstanceOf(RefreshTokenService.FamilyStateChangedException.class);
        assertThat(family.getStatus()).isEqualTo(TokenFamilyStatus.REVOKED);
    }

    @Test
    void rejectsRefreshWhenSessionIsRevoked() {
        session.revoke();
        stubRefreshBeforeSessionLookup();

        assertThatThrownBy(() -> service.refresh(new RefreshTokenService.RefreshRequest(
                "presented", "key", "fingerprint")))
                .isInstanceOf(AuthenticationFailureException.class);
    }

    @Test
    void rejectsRefreshWhenAccountIsInactive() {
        account.disable();
        stubRefreshBeforeSessionLookup();

        assertThatThrownBy(() -> service.refresh(new RefreshTokenService.RefreshRequest(
                "presented", "key", "fingerprint")))
                .isInstanceOf(AuthenticationFailureException.class);
    }

    @Test
    void mismatchedReuseRevokesTheEntireFamily() {
        when(familyRepository.findByActiveTokenHash(TokenDigest.sha256("replayed")))
                .thenReturn(Optional.empty());
        when(consumedRepository.findByTokenHash(TokenDigest.sha256("replayed")))
                .thenReturn(Optional.of(new ConsumedRefreshToken(
                        family.getId(), TokenDigest.sha256("replayed"), NOW.minusSeconds(1))));
        when(familyRepository.findByIdForUpdate(family.getId())).thenReturn(Optional.of(family));
        when(replayRepository.findByFamilyIdAndIdempotencyKeyForUpdate(family.getId(), "different"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.refresh(new RefreshTokenService.RefreshRequest(
                "replayed", "different", "fingerprint")))
                .isInstanceOf(AuthenticationFailureException.class);

        assertThat(family.getStatus()).isEqualTo(TokenFamilyStatus.REVOKED);
        verify(familyRepository).save(family);
    }

    private void stubRefreshBeforeSessionLookup() {
        when(familyRepository.findByActiveTokenHash(TokenDigest.sha256("presented")))
                .thenReturn(Optional.of(family));
        when(familyRepository.findByIdForUpdate(family.getId())).thenReturn(Optional.of(family));
        when(replayRepository.findByFamilyIdAndIdempotencyKeyForUpdate(family.getId(), "key"))
                .thenReturn(Optional.empty());
        when(consumedRepository.countByFamilyId(family.getId())).thenReturn(0L);
        when(sessionRepository.findById(session.getId())).thenReturn(Optional.of(session));
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
    }
}
