package com.lifeos.identity.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Validates JWTs and then performs the durable session check required by ADR-020. Signature and
 * claims are an early filter; a valid JWT alone can never bypass PostgreSQL revocation state.
 */
@Service
public class JwtValidationService {

    private final JwtDecoder jwtDecoder;
    private final AuthSessionRepository sessionRepository;
    private final Clock clock;

    @Autowired
    public JwtValidationService(JwtDecoder jwtDecoder, AuthSessionRepository sessionRepository) {
        this(jwtDecoder, sessionRepository, Clock.systemUTC());
    }

    JwtValidationService(
            JwtDecoder jwtDecoder, AuthSessionRepository sessionRepository, Clock clock) {
        this.jwtDecoder = jwtDecoder;
        this.sessionRepository = sessionRepository;
        this.clock = clock;
    }

    /**
     * Validates a raw bearer token without logging its value.
     *
     * @param rawToken raw token from the Authorization header
     * @return validated subject context
     */
    @SuppressWarnings("PMD.PreserveStackTrace")
    public AuthenticatedSubject validate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new AuthenticationFailureException();
        }
        try {
            Jwt jwt = jwtDecoder.decode(rawToken);
            UUID accountId = UUID.fromString(requiredClaim(jwt, "sub"));
            UUID sessionId = UUID.fromString(requiredClaim(jwt, "session_id"));
            String accessTokenProof = TokenDigest.sha256(rawToken);
            Instant now = clock.instant();
            AuthSession session = sessionRepository.findById(sessionId)
                    .filter(candidate -> candidate.getAccountId().equals(accountId))
                    .filter(candidate -> !candidate.isRevoked())
                    .filter(candidate -> candidate.getExpiresAt().isAfter(now))
                    .filter(candidate -> TokenDigest.matches(candidate.getAccessTokenHash(), accessTokenProof))
                    .orElseThrow(AuthenticationFailureException::new);
            return new AuthenticatedSubject(
                    accountId, sessionId, session.getAuthenticationMethod().name(), accessTokenProof);
        } catch (JwtException | IllegalArgumentException exception) {
            // The cause is deliberately dropped. Decode details must not reach the caller.
            throw new AuthenticationFailureException();
        }
    }

    private String requiredClaim(Jwt jwt, String name) {
        Object value = jwt.getClaims().get(name);
        if (value == null || value.toString().isBlank()) {
            throw new AuthenticationFailureException();
        }
        return value.toString();
    }
}
