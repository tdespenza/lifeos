package com.lifeos.identity.auth;

import com.lifeos.identity.account.UserAccount;
import com.lifeos.identity.account.UserAccountRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates short-lived signed access tokens and durable session metadata.
 *
 * <p>Refresh-token rotation, asymmetric key/JWKS publication, and downstream verification policy
 * remain the Story 1.5 hardening surface. This class is the single authority boundary so those
 * capabilities can evolve without duplicating login behavior.
 */
@Service
public class JwtSessionTokenAuthority implements SessionTokenAuthority {

    private static final String TOKEN_TYPE = "Bearer";

    private final JwtEncoder jwtEncoder;
    private final AuthSessionRepository sessionRepository;
    private final UserAccountRepository accountRepository;
    private final PasswordCredentialRepository credentialRepository;
    private final IdentityAuthProperties properties;
    private final Clock clock;

    /**
     * Creates the JWT session/token authority.
     *
     * @param jwtEncoder signed-token encoder
     * @param sessionRepository durable session repository
     * @param accountRepository account repository used for capacity locking
     * @param credentialRepository credential repository used for state revalidation
     * @param properties authentication properties
    */
    @Autowired
    public JwtSessionTokenAuthority(
            JwtEncoder jwtEncoder,
            AuthSessionRepository sessionRepository,
            UserAccountRepository accountRepository,
            PasswordCredentialRepository credentialRepository,
            IdentityAuthProperties properties) {
        this(jwtEncoder, sessionRepository, accountRepository, credentialRepository, properties,
                Clock.systemUTC());
    }

    /**
     * Creates the authority with an injectable clock for deterministic tests.
     *
     * @param jwtEncoder signed-token encoder
     * @param sessionRepository durable session repository
     * @param accountRepository account repository
     * @param credentialRepository credential repository
     * @param properties authentication properties
     * @param clock time source
     */
    public JwtSessionTokenAuthority(
            JwtEncoder jwtEncoder,
            AuthSessionRepository sessionRepository,
            UserAccountRepository accountRepository,
            PasswordCredentialRepository credentialRepository,
            IdentityAuthProperties properties,
            Clock clock) {
        this.jwtEncoder = jwtEncoder;
        this.sessionRepository = sessionRepository;
        this.accountRepository = accountRepository;
        this.credentialRepository = credentialRepository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Locks the account, enforces active-session capacity, signs a JWT, and persists only its digest.
     *
     * @param account active authenticated account
     * @return signed access-token response
     */
    @Override
    @Transactional
    public LoginResponse createSession(UserAccount account) {
        try {
            UserAccount lockedAccount = accountRepository.findByIdForUpdate(account.getId())
                    .orElseThrow(AuthenticationFailureException::new);
            if (!lockedAccount.isActive()) {
                throw new AuthenticationFailureException();
            }
            PasswordCredential lockedCredential = credentialRepository
                    .findByAccountIdForUpdate(lockedAccount.getId())
                    .orElseThrow(AuthenticationFailureException::new);
            if (!lockedCredential.isActive()) {
                throw new AuthenticationFailureException();
            }

            Instant issuedAt = clock.instant();
            Instant expiresAt = issuedAt.plus(properties.getAccessTokenTtl());
            if (sessionRepository.countActiveByAccountId(lockedAccount.getId(), issuedAt)
                    >= properties.getMaxSessionsPerAccount()) {
                throw new SessionCapacityExceededException();
            }

            UUID sessionId = UUID.randomUUID();
            JwtClaimsSet claims = JwtClaimsSet.builder()
                    .issuer(properties.getJwt().getIssuer())
                    .subject(lockedAccount.getId().toString())
                    .id(sessionId.toString())
                    .issuedAt(issuedAt)
                    .expiresAt(expiresAt)
                    .claim("session_id", sessionId.toString())
                    .build();
            JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
            String accessToken = jwtEncoder.encode(JwtEncoderParameters.from(header, claims))
                    .getTokenValue();
            sessionRepository.saveAndFlush(new AuthSession(
                    sessionId,
                    lockedAccount,
                    TokenDigest.sha256(accessToken),
                    issuedAt,
                    expiresAt));
            return new LoginResponse(
                    sessionId,
                    accessToken,
                    TOKEN_TYPE,
                    properties.getAccessTokenTtl().toSeconds());
        } catch (SessionCapacityExceededException | AuthenticationFailureException exception) {
            throw exception;
        } catch (DataAccessException | AuthenticationDependencyUnavailableException exception) {
            throw new AuthenticationDependencyUnavailableException(exception);
        } catch (RuntimeException exception) {
            throw new AuthenticationDependencyUnavailableException(exception);
        }
    }
}
