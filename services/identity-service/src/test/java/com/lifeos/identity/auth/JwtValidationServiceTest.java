package com.lifeos.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

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

        AuthenticatedSubject subject = service.validate(rawToken);

        assertThat(subject.accountId()).isEqualTo(accountId);
        assertThat(subject.sessionId()).isEqualTo(sessionId);
        assertThat(subject.authenticationMethod()).isEqualTo("PASSWORD");
    }

    @Test
    void rejectsDurableSessionThatExpiredEvenWhenJwtExpiryIsLater() {
        String rawToken = "signed-access-token";
        when(jwtDecoder.decode(rawToken)).thenReturn(jwt(rawToken, NOW.plusSeconds(300)));
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session(NOW.minusSeconds(1))));

        assertThatThrownBy(() -> service.validate(rawToken))
                .isInstanceOf(AuthenticationFailureException.class);
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
        UserAccount account = new UserAccount("ada@example.com", "Ada Lovelace");
        org.springframework.test.util.ReflectionTestUtils.setField(account, "id", accountId);
        return new AuthSession(
                sessionId,
                account,
                SessionAuthenticationMethod.PASSWORD,
                TokenDigest.sha256("signed-access-token"),
                NOW.minusSeconds(30),
                expiresAt);
    }
}
