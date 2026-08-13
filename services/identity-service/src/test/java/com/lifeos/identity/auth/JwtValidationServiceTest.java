package com.lifeos.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

import com.lifeos.identity.account.UserAccount;
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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

@ExtendWith(MockitoExtension.class)
class JwtValidationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-12T00:00:00Z");

    @Mock
    private JwtDecoder jwtDecoder;

    @Mock
    private AuthSessionRepository sessionRepository;

    private UUID accountId;
    private UUID sessionId;
    private JwtValidationService service;

    @BeforeEach
    void setUp() {
        accountId = UUID.randomUUID();
        sessionId = UUID.randomUUID();
        service = new JwtValidationService(
                jwtDecoder, sessionRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void validatesSignatureClaimsAndDurableSessionState() {
        String rawToken = "signed-access-token";
        when(jwtDecoder.decode(rawToken)).thenReturn(jwt(rawToken, NOW.plusSeconds(300)));
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session(NOW.plusSeconds(300))));
        when(sessionRepository.touchLastUsedAt(sessionId, NOW)).thenReturn(1);

        AuthenticatedSubject subject = service.validate(rawToken);

        assertThat(subject.accountId()).isEqualTo(accountId);
        assertThat(subject.sessionId()).isEqualTo(sessionId);
        assertThat(subject.authenticationMethod()).isEqualTo("PASSWORD");
        assertThat(subject.accessTokenProof()).isEqualTo(TokenDigest.sha256(rawToken));
    }

    @Test
    void rejectsDurableSessionThatExpiredEvenWhenJwtExpiryIsLater() {
        String rawToken = "signed-access-token";
        when(jwtDecoder.decode(rawToken)).thenReturn(jwt(rawToken, NOW.plusSeconds(300)));
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session(NOW.minusSeconds(1))));

        assertThatThrownBy(() -> service.validate(rawToken))
                .isInstanceOf(AuthenticationFailureException.class);
    }

    @Test
    void rejectsRevokedSession() {
        AuthSession revoked = session(NOW.plusSeconds(300));
        revoked.revoke();
        when(jwtDecoder.decode("signed-access-token")).thenReturn(jwt("signed-access-token", NOW.plusSeconds(300)));
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(revoked));

        assertThatThrownBy(() -> service.validate("signed-access-token"))
                .isInstanceOf(AuthenticationFailureException.class);
    }

    @Test
    void rejectsSessionOwnedByAnotherAccount() {
        UUID otherAccountId = UUID.randomUUID();
        when(jwtDecoder.decode("signed-access-token")).thenReturn(jwt("signed-access-token", NOW.plusSeconds(300)));
        when(sessionRepository.findById(sessionId))
                .thenReturn(Optional.of(sessionFor(otherAccountId, NOW.plusSeconds(300))));

        assertThatThrownBy(() -> service.validate("signed-access-token"))
                .isInstanceOf(AuthenticationFailureException.class);
    }

    @Test
    void rejectsValidlySignedTokenThatDoesNotMatchThePersistedDigest() {
        String rawToken = "another-signed-access-token";
        when(jwtDecoder.decode(rawToken)).thenReturn(jwt(rawToken, NOW.plusSeconds(300)));
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session(NOW.plusSeconds(300))));

        assertThatThrownBy(() -> service.validate(rawToken))
                .isInstanceOf(AuthenticationFailureException.class);
    }

    @Test
    void rejectsWhenLastUseUpdateObservesAConcurrentRevocation() {
        String rawToken = "signed-access-token";
        when(jwtDecoder.decode(rawToken)).thenReturn(jwt(rawToken, NOW.plusSeconds(300)));
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session(NOW.plusSeconds(300))));
        when(sessionRepository.touchLastUsedAt(sessionId, NOW)).thenReturn(0);

        assertThatThrownBy(() -> service.validate(rawToken))
                .isInstanceOf(AuthenticationFailureException.class);
    }

    @Test
    void rejectsTokenWithoutSessionIdClaim() {
        when(jwtDecoder.decode("signed-access-token")).thenReturn(Jwt.withTokenValue("signed-access-token")
                .header("alg", "HS256")
                .claim("sub", accountId.toString())
                .issuedAt(NOW.minusSeconds(30))
                .expiresAt(NOW.plusSeconds(300))
                .build());

        assertThatThrownBy(() -> service.validate("signed-access-token"))
                .isInstanceOf(AuthenticationFailureException.class);
        verifyNoInteractions(sessionRepository);
    }

    @Test
    void rejectsDecoderFailureWithoutExposingItsCause() {
        when(jwtDecoder.decode("malformed")).thenThrow(new JwtException("malformed token details"));

        assertThatThrownBy(() -> service.validate("malformed"))
                .isInstanceOf(AuthenticationFailureException.class)
                .hasMessage("The supplied credentials could not be verified.");
    }

    @Test
    void rejectsNullAndBlankTokensBeforeDecoding() {
        assertThatThrownBy(() -> service.validate(null))
                .isInstanceOf(AuthenticationFailureException.class);
        assertThatThrownBy(() -> service.validate("  "))
                .isInstanceOf(AuthenticationFailureException.class);
        verifyNoInteractions(jwtDecoder, sessionRepository);
    }

    private Jwt jwt(String rawToken, Instant expiresAt) {
        return Jwt.withTokenValue(rawToken)
                .header("alg", "HS256")
                .claim("sub", accountId.toString())
                .claim("session_id", sessionId.toString())
                .issuedAt(NOW.minusSeconds(30))
                .expiresAt(expiresAt)
                .build();
    }

    private AuthSession session(Instant expiresAt) {
        return sessionFor(accountId, expiresAt);
    }

    private AuthSession sessionFor(UUID ownerId, Instant expiresAt) {
        UserAccount account = new UserAccount("ada@example.com", "Ada Lovelace");
        org.springframework.test.util.ReflectionTestUtils.setField(account, "id", ownerId);
        return new AuthSession(
                sessionId,
                account,
                SessionAuthenticationMethod.PASSWORD,
                TokenDigest.sha256("signed-access-token"),
                NOW.minusSeconds(30),
                expiresAt);
    }
}
