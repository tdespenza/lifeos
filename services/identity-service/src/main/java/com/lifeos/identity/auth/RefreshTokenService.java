package com.lifeos.identity.auth;

import com.lifeos.identity.account.UserAccount;
import com.lifeos.identity.account.UserAccountRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns one-time refresh-token rotation and replay detection.
 *
 * <p>The family row is pessimistically locked for the entire bounded database transaction. This
 * gives concurrent requests a single linearization point: one request consumes the predecessor and
 * creates a successor; a replay is either the one explicitly permitted idempotent retry or a
 * family-revoking reuse attempt.
 */
@Service
public class RefreshTokenService {

    private static final String TOKEN_TYPE = "Bearer";

    private final TokenFamilyRepository familyRepository;
    private final ConsumedRefreshTokenRepository consumedRepository;
    private final RefreshReplayRecordRepository replayRepository;
    private final AuthSessionRepository sessionRepository;
    private final UserAccountRepository accountRepository;
    private final JwtEncoder jwtEncoder;
    private final OpaqueTokenGenerator tokenGenerator;
    private final RefreshResponseCipher responseCipher;
    private final IdentityAuthProperties properties;
    private final Clock clock;

    @Autowired
    public RefreshTokenService(
            TokenFamilyRepository familyRepository,
            ConsumedRefreshTokenRepository consumedRepository,
            RefreshReplayRecordRepository replayRepository,
            AuthSessionRepository sessionRepository,
            UserAccountRepository accountRepository,
            JwtEncoder jwtEncoder,
            OpaqueTokenGenerator tokenGenerator,
            RefreshResponseCipher responseCipher,
            IdentityAuthProperties properties) {
        this(familyRepository, consumedRepository, replayRepository, sessionRepository,
                accountRepository, jwtEncoder, tokenGenerator, responseCipher, properties,
                Clock.systemUTC());
    }

    public RefreshTokenService(
            TokenFamilyRepository familyRepository,
            ConsumedRefreshTokenRepository consumedRepository,
            RefreshReplayRecordRepository replayRepository,
            AuthSessionRepository sessionRepository,
            UserAccountRepository accountRepository,
            JwtEncoder jwtEncoder,
            OpaqueTokenGenerator tokenGenerator,
            RefreshResponseCipher responseCipher,
            IdentityAuthProperties properties,
            Clock clock) {
        this.familyRepository = familyRepository;
        this.consumedRepository = consumedRepository;
        this.replayRepository = replayRepository;
        this.sessionRepository = sessionRepository;
        this.accountRepository = accountRepository;
        this.jwtEncoder = jwtEncoder;
        this.tokenGenerator = tokenGenerator;
        this.responseCipher = responseCipher;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Creates the first opaque credential in a refresh family.
     *
     * @param accountId account owning the family
     * @param sessionId durable access-token session
     * @param issuedAt issuance instant
     * @return family and raw credential for the response boundary
     */
    public IssuedRefreshToken createFamily(UUID accountId, UUID sessionId, Instant issuedAt) {
        IdentityAuthProperties.Jwt jwt = properties.getJwt();
        Instant familyExpiresAt = issuedAt.plus(jwt.getRefreshFamilyTtl());
        Instant refreshExpiresAt = min(issuedAt.plus(jwt.getRefreshTokenTtl()), familyExpiresAt);
        Instant idleExpiresAt = min(issuedAt.plus(jwt.getRefreshIdleTtl()), familyExpiresAt);
        String rawToken = tokenGenerator.next();
        TokenFamily family = new TokenFamily(
                UUID.randomUUID(),
                accountId,
                sessionId,
                TokenDigest.sha256(rawToken),
                issuedAt,
                refreshExpiresAt,
                familyExpiresAt,
                idleExpiresAt);
        familyRepository.save(family);
        return new IssuedRefreshToken(rawToken, refreshExpiresAt);
    }

    /**
     * Rotates a presented credential exactly once, with one bounded matching retry.
     *
     * @param request refresh request metadata
     * @return new access and refresh credentials
     */
    @Transactional(noRollbackFor = FamilyStateChangedException.class)
    public LoginResponse refresh(RefreshRequest request) {
        validateRequest(request);
        String presentedHash = TokenDigest.sha256(request.refreshToken());
        try {
            TokenFamily family = familyRepository.findByActiveTokenHash(presentedHash)
                    .map(TokenFamily::getId)
                    .or(() -> consumedRepository.findByTokenHash(presentedHash)
                            .map(ConsumedRefreshToken::getFamilyId))
                    .flatMap(familyRepository::findByIdForUpdate)
                    .orElseThrow(AuthenticationFailureException::new);

            RefreshReplayRecord existing = replayRepository
                    .findByFamilyIdAndIdempotencyKeyForUpdate(family.getId(), request.idempotencyKey())
                    .orElse(null);
            if (existing != null) {
                return handleExistingReplay(family, existing, request.requestFingerprint());
            }
            if (!family.getActiveTokenHash().equals(presentedHash)) {
                return revokeAndReject(family);
            }
            Instant now = clock.instant();
            if (family.getStatus() != TokenFamilyStatus.ACTIVE
                    || !now.isBefore(family.getRefreshExpiresAt())
                    || !now.isBefore(family.getIdleExpiresAt())
                    || !now.isBefore(family.getFamilyExpiresAt())) {
                family.expire();
                familyRepository.save(family);
                throw new FamilyStateChangedException();
            }
            if (consumedRepository.countByFamilyId(family.getId())
                    >= properties.getJwt().getMaxRefreshReplayRecordsPerFamily()) {
                return revokeAndReject(family);
            }

            RefreshReplayRecord replay = new RefreshReplayRecord(
                    family.getId(), request.idempotencyKey(), request.requestFingerprint(),
                    now.plus(properties.getJwt().getRefreshReplayTtl()));
            replayRepository.saveAndFlush(replay);

            AuthSession session = sessionRepository.findById(family.getSessionId())
                    .orElseThrow(AuthenticationFailureException::new);
            UserAccount account = accountRepository.findById(family.getAccountId())
                    .orElseThrow(AuthenticationFailureException::new);
            if (!account.isActive() || session.isRevoked()) {
                throw new AuthenticationFailureException();
            }

            String successorRefreshToken = tokenGenerator.next();
            Instant successorAccessExpiresAt = now.plus(properties.getAccessTokenTtl());
            Instant successorRefreshExpiresAt = min(
                    now.plus(properties.getJwt().getRefreshTokenTtl()), family.getFamilyExpiresAt());
            Instant successorIdleExpiresAt = min(
                    now.plus(properties.getJwt().getRefreshIdleTtl()), family.getFamilyExpiresAt());
            String successorAccessToken = issueAccessToken(
                    account, session, now, session.getAuthenticationMethod());
            LoginResponse response = new LoginResponse(
                    session.getId(),
                    successorAccessToken,
                    TOKEN_TYPE,
                    properties.getAccessTokenTtl().toSeconds(),
                    successorRefreshToken,
                    Duration.between(now, successorRefreshExpiresAt).toSeconds());

            consumedRepository.save(new ConsumedRefreshToken(
                    family.getId(), presentedHash, now));
            family.rotate(
                    TokenDigest.sha256(successorRefreshToken),
                    now,
                    successorRefreshExpiresAt,
                    successorIdleExpiresAt);
            familyRepository.save(family);
            session.replaceAccessTokenHash(TokenDigest.sha256(successorAccessToken));
            session.extendExpiresAt(successorAccessExpiresAt);
            sessionRepository.save(session);
            replay.commit(responseCipher.encrypt(response));
            replayRepository.save(replay);
            return response;
        } catch (AuthenticationFailureException exception) {
            throw exception;
        } catch (DataAccessException | AuthenticationDependencyUnavailableException exception) {
            throw new AuthenticationDependencyUnavailableException(exception);
        } catch (RuntimeException exception) {
            throw new AuthenticationDependencyUnavailableException(exception);
        }
    }

    private LoginResponse handleExistingReplay(
            TokenFamily family, RefreshReplayRecord replay, String requestFingerprint) {
        Instant now = clock.instant();
        if (replay.getState() == RefreshReplayState.COMMITTED
                && replay.getRequestFingerprint().equals(requestFingerprint)
                && replay.getRetryCount() == 0
                && now.isBefore(replay.getExpiresAt())) {
            LoginResponse response = responseCipher.decrypt(replay.getEncryptedResponse());
            replay.consumeRetry();
            replayRepository.save(replay);
            return response;
        }
        return revokeAndReject(family);
    }

    private LoginResponse revokeAndReject(TokenFamily family) {
        family.revoke();
        familyRepository.save(family);
        throw new FamilyStateChangedException();
    }

    private void validateRequest(RefreshRequest request) {
        if (request == null || request.refreshToken() == null || request.refreshToken().isBlank()
                || request.idempotencyKey() == null || request.idempotencyKey().isBlank()
                || request.idempotencyKey().length() > 128
                || request.requestFingerprint() == null || request.requestFingerprint().isBlank()) {
            throw new AuthenticationFailureException();
        }
    }

    private String issueAccessToken(
            UserAccount account,
            AuthSession session,
            Instant issuedAt,
            SessionAuthenticationMethod authenticationMethod) {
        Instant expiresAt = issuedAt.plus(properties.getAccessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.getJwt().getIssuer())
                .audience(java.util.List.of(properties.getJwt().getAudience()))
                .subject(account.getId().toString())
                .id(session.getId().toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("session_id", session.getId().toString())
                .claim("auth_method", authenticationMethod.name())
                .build();
        boolean rsa = properties.getJwt().getPrivateKeyPem() != null
                && !properties.getJwt().getPrivateKeyPem().isBlank();
        JwsHeader.Builder header = rsa
                ? JwsHeader.with(SignatureAlgorithm.RS256)
                : JwsHeader.with(MacAlgorithm.HS256);
        return jwtEncoder.encode(JwtEncoderParameters.from(header
                .keyId(properties.getJwt().getSigningKeyId())
                .type("JWT")
                .build(), claims)).getTokenValue();
    }

    private Instant min(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }

    public record IssuedRefreshToken(String value, Instant expiresAt) {
    }

    public record RefreshRequest(
            String refreshToken,
            String idempotencyKey,
            String requestFingerprint) {
    }

    /**
     * Signals a terminal family state change that must survive the sanitized 401 response.
     */
    public static class FamilyStateChangedException extends AuthenticationFailureException {

        public FamilyStateChangedException() {
            super();
        }
    }
}
