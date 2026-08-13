package com.lifeos.identity.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
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

    private static final Logger log = LoggerFactory.getLogger(JwtValidationService.class);

    private final JwtDecoder jwtDecoder;
    private final AuthSessionRepository sessionRepository;
    private final SessionRevocationCache revocationCache;
    private final Clock clock;

    @Autowired
    public JwtValidationService(
            JwtDecoder jwtDecoder,
            AuthSessionRepository sessionRepository,
            SessionRevocationCache revocationCache) {
        this(jwtDecoder, sessionRepository, revocationCache, Clock.systemUTC());
    }

    /**
     * Compatibility constructor for callers that do not configure a Redis acceleration cache.
     *
     * @param jwtDecoder signed-token decoder
     * @param sessionRepository durable session authority
     */
    public JwtValidationService(JwtDecoder jwtDecoder, AuthSessionRepository sessionRepository) {
        this(jwtDecoder, sessionRepository, SessionRevocationCache.NOOP, Clock.systemUTC());
    }

    JwtValidationService(
            JwtDecoder jwtDecoder, AuthSessionRepository sessionRepository, Clock clock) {
        this(jwtDecoder, sessionRepository, SessionRevocationCache.NOOP, clock);
    }

    JwtValidationService(
            JwtDecoder jwtDecoder,
            AuthSessionRepository sessionRepository,
            SessionRevocationCache revocationCache,
            Clock clock) {
        this.jwtDecoder = jwtDecoder;
        this.sessionRepository = sessionRepository;
        this.revocationCache = revocationCache;
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
            if (revocationCache.isRevoked(sessionId).orElse(false)) {
                throw new AuthenticationFailureException();
            }
            AuthSession session = sessionRepository.findById(sessionId)
                    .filter(candidate -> candidate.getAccountId().equals(accountId))
                    .orElseThrow(AuthenticationFailureException::new);
            if (session.isRevoked()) {
                revocationCache.markRevoked(session.getId(), session.getExpiresAt());
                throw new AuthenticationFailureException();
            }
            if (!session.getExpiresAt().isAfter(now)
                    || !TokenDigest.matches(session.getAccessTokenHash(), accessTokenProof)) {
                throw new AuthenticationFailureException();
            }
            try {
                if (sessionRepository.touchLastUsedAt(session.getId(), now) != 1) {
                    throw new AuthenticationFailureException();
                }
            } catch (DataAccessException exception) {
                // Last-use metadata is not an authorization input. A transient write failure must
                // not turn an otherwise valid, durably checked bearer token into a 500 response.
                log.atWarn()
                        .addKeyValue("event", "session_last_used_update_failed")
                        .addKeyValue("dependencyException", exception.getClass().getName())
                        .log("Session last-use metadata could not be updated");
            }
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
