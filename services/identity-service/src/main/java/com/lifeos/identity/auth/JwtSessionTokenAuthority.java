package com.lifeos.identity.auth;

import com.lifeos.identity.account.UserAccount;
import com.lifeos.identity.account.UserAccountRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates short-lived signed access tokens and durable session metadata.
 *
 * <p>Refresh-token rotation, asymmetric key/JWKS publication, and downstream verification policy
 * are implemented behind this single authority boundary so password, OIDC, and passkey flows do
 * not duplicate token behavior.
 */
@Service
public class JwtSessionTokenAuthority implements SessionTokenAuthority {

    private static final String TOKEN_TYPE = "Bearer";
    private static final Logger log = LoggerFactory.getLogger(JwtSessionTokenAuthority.class);

    private final JwtEncoder jwtEncoder;
    private final AuthSessionRepository sessionRepository;
    private final UserAccountRepository accountRepository;
    private final PasswordCredentialRepository credentialRepository;
    private final RefreshTokenService refreshTokenService;
    private final JwtSigningMaterial signingMaterial;
    private final IdentityAuthProperties properties;
    private final Clock clock;

    /**
     * Creates the JWT session/token authority.
     *
     * @param jwtEncoder signed-token encoder
     * @param sessionRepository durable session repository
     * @param accountRepository account repository used for capacity locking
     * @param credentialRepository credential repository used for state revalidation
     * @param refreshTokenService refresh-family authority
     * @param signingMaterial resolved JWT signing material
     * @param properties authentication properties
     */
    @Autowired
    public JwtSessionTokenAuthority(
            JwtEncoder jwtEncoder,
            AuthSessionRepository sessionRepository,
            UserAccountRepository accountRepository,
            PasswordCredentialRepository credentialRepository,
            RefreshTokenService refreshTokenService,
            JwtSigningMaterial signingMaterial,
            IdentityAuthProperties properties) {
        this(jwtEncoder, sessionRepository, accountRepository, credentialRepository, refreshTokenService,
                signingMaterial, properties,
                Clock.systemUTC());
    }

    /**
     * Compatibility constructor for pre-Story-1.5 unit tests and test doubles.
     *
     * @deprecated production wiring must use the constructor with refresh-token services
     */
    @Deprecated
    public JwtSessionTokenAuthority(
            JwtEncoder jwtEncoder,
            AuthSessionRepository sessionRepository,
            UserAccountRepository accountRepository,
            PasswordCredentialRepository credentialRepository,
            IdentityAuthProperties properties) {
        this(jwtEncoder, sessionRepository, accountRepository, credentialRepository, null,
                JwtSigningMaterial.from(properties), properties, Clock.systemUTC());
    }

    /**
     * Compatibility constructor with an injectable clock for the original session tests.
     *
     * @deprecated production wiring must use the constructor with refresh-token services
     */
    @Deprecated
    public JwtSessionTokenAuthority(
            JwtEncoder jwtEncoder,
            AuthSessionRepository sessionRepository,
            UserAccountRepository accountRepository,
            PasswordCredentialRepository credentialRepository,
            IdentityAuthProperties properties,
            Clock clock) {
        this(jwtEncoder, sessionRepository, accountRepository, credentialRepository, null,
                JwtSigningMaterial.from(properties), properties, clock);
    }

    /**
     * Creates the authority with an injectable clock for deterministic tests.
     *
     * @param jwtEncoder signed-token encoder
     * @param sessionRepository durable session repository
     * @param accountRepository account repository
     * @param credentialRepository credential repository
     * @param refreshTokenService refresh-family authority
     * @param signingMaterial resolved JWT signing material
     * @param properties authentication properties
     * @param clock time source
     */
    public JwtSessionTokenAuthority(
            JwtEncoder jwtEncoder,
            AuthSessionRepository sessionRepository,
            UserAccountRepository accountRepository,
            PasswordCredentialRepository credentialRepository,
            RefreshTokenService refreshTokenService,
            JwtSigningMaterial signingMaterial,
            IdentityAuthProperties properties,
            Clock clock) {
        this.jwtEncoder = jwtEncoder;
        this.sessionRepository = sessionRepository;
        this.accountRepository = accountRepository;
        this.credentialRepository = credentialRepository;
        this.refreshTokenService = refreshTokenService;
        this.signingMaterial = signingMaterial;
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
        return createSession(account, SessionAuthenticationMethod.PASSWORD);
    }

    /**
     * Creates a session after revalidating the account and the credential required by the
     * authentication method. OIDC accounts deliberately have no local password credential.
     *
     * @param account account selected by the verified authentication result
     * @param authenticationMethod authentication method already verified at the protocol boundary
     * @return signed access-token result
     */
    @Override
    @Transactional
    public LoginResponse createSession(
            UserAccount account, SessionAuthenticationMethod authenticationMethod) {
        try {
            if (authenticationMethod == null) {
                throw new AuthenticationFailureException();
            }
            UserAccount lockedAccount = accountRepository.findByIdForUpdate(account.getId())
                    .orElseThrow(AuthenticationFailureException::new);
            if (!lockedAccount.isActive()) {
                throw new AuthenticationFailureException();
            }
            if (authenticationMethod == SessionAuthenticationMethod.PASSWORD) {
                PasswordCredential lockedCredential = credentialRepository
                        .findByAccountIdForUpdate(lockedAccount.getId())
                        .orElseThrow(AuthenticationFailureException::new);
                if (!lockedCredential.isActive()) {
                    throw new AuthenticationFailureException();
                }
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
                    .audience(java.util.List.of(properties.getJwt().getAudience()))
                    .subject(lockedAccount.getId().toString())
                    .id(UUID.randomUUID().toString())
                    .issuedAt(issuedAt)
                    .expiresAt(expiresAt)
                    .claim("session_id", sessionId.toString())
                    .claim("auth_method", authenticationMethod.name())
                    .build();
            String accessToken = jwtEncoder.encode(encoderParameters(claims))
                    .getTokenValue();
            AuthSession session = new AuthSession(
                    sessionId,
                    lockedAccount,
                    authenticationMethod,
                    TokenDigest.sha256(accessToken),
                    issuedAt,
                    expiresAt);
            sessionRepository.saveAndFlush(session);
            RefreshTokenService.IssuedRefreshToken refresh = refreshTokenService == null
                    ? null
                    : refreshTokenService.createFamily(lockedAccount.getId(), sessionId, issuedAt);
            return new LoginResponse(
                    sessionId,
                    accessToken,
                    TOKEN_TYPE,
                    properties.getAccessTokenTtl().toSeconds(),
                    refresh == null ? null : refresh.value(),
                    refresh == null ? 0 : java.time.Duration.between(issuedAt, refresh.expiresAt()).toSeconds());
        } catch (SessionCapacityExceededException | AuthenticationFailureException exception) {
            throw exception;
        } catch (DataAccessException | JwtException exception) {
            log.atError()
                    .addKeyValue("event", "session_token_issuance_failed")
                    .addKeyValue("dependencyException", exception.getClass().getName())
                    .log("Session token issuance dependency failed");
            throw new AuthenticationDependencyUnavailableException(exception);
        } catch (RuntimeException exception) {
            log.atError()
                    .addKeyValue("event", "session_token_issuance_failed")
                    .addKeyValue("dependencyException", exception.getClass().getName())
                    .log("Session token issuance failed");
            if (exception instanceof AuthenticationDependencyUnavailableException dependencyException) {
                throw dependencyException;
            }
            if (exception instanceof DataAccessException dataAccessException) {
                throw new AuthenticationDependencyUnavailableException(dataAccessException);
            }
            throw exception;
        }
    }

    private JwtEncoderParameters encoderParameters(JwtClaimsSet claims) {
        return JwtEncoderParameters.from(signingMaterial.jwtHeader(), claims);
    }
}
