package com.lifeos.notification.endpoint;

import com.lifeos.events.v1.NotificationChannel;
import com.lifeos.notification.access.NotificationSubject;
import com.lifeos.notification.audit.NotificationSecurityAuditOutcome;
import com.lifeos.notification.audit.NotificationSecurityAuditService;
import com.lifeos.notification.config.NotificationProperties;
import com.lifeos.notification.persistence.EndpointRegistrationIdempotencyState;
import com.lifeos.notification.persistence.NotificationEndpoint;
import com.lifeos.notification.persistence.NotificationEndpointRegistrationIdempotency;
import com.lifeos.notification.persistence.NotificationEndpointRegistrationIdempotencyRepository;
import com.lifeos.notification.persistence.NotificationEndpointRepository;
import com.lifeos.notification.security.EndpointCipher;
import com.lifeos.notification.security.SensitiveValueDigest;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/** Owner-scoped endpoint enrollment and revocation service. */
@Service
public class NotificationEndpointService {

    private static final String KEY_DOMAIN = "lifeos:notification-endpoint:key:v1";
    private static final String REQUEST_DOMAIN = "lifeos:notification-endpoint:request:v1";
    private static final String DESTINATION_DOMAIN = "lifeos:notification-endpoint:destination:v1";

    private final NotificationEndpointRegistrationTransactions transactions;
    private final NotificationEndpointRegistrationIdempotencyRepository idempotencyRepository;
    private final NotificationEndpointRepository endpointRepository;
    private final EndpointCipher cipher;
    private final NotificationProperties properties;
    private final Clock clock;
    private final NotificationSecurityAuditService auditService;

    public NotificationEndpointService(
            NotificationEndpointRegistrationTransactions transactions,
            NotificationEndpointRegistrationIdempotencyRepository idempotencyRepository,
            NotificationEndpointRepository endpointRepository,
            EndpointCipher cipher,
            NotificationProperties properties,
            Clock clock,
            NotificationSecurityAuditService auditService) {
        this.transactions = transactions;
        this.idempotencyRepository = idempotencyRepository;
        this.endpointRepository = endpointRepository;
        this.cipher = cipher;
        this.properties = properties;
        this.clock = clock;
        this.auditService = auditService;
    }

    /** Creates an encrypted email/push endpoint once for a caller-scoped retry key. */
    public EndpointRegistrationResult register(
            NotificationSubject subject, NotificationChannel channel, String destination, String idempotencyKey) {
        EndpointRegistrationResult result;
        try {
            if (channel == null || channel == NotificationChannel.REALTIME) {
                throw new IllegalArgumentException("only email and push endpoints can be enrolled");
            }
            String normalizedDestination = normalize(channel, destination);
            String keyHash = digest(KEY_DOMAIN, idempotencyKey);
            String requestFingerprint = digest(REQUEST_DOMAIN, channel + "\n" + normalizedDestination);
            String destinationHash = digest(DESTINATION_DOMAIN, channel + "\n" + normalizedDestination);
            try {
                result = transactions.registerFresh(
                        subject.accountId(),
                        channel,
                        cipher.encrypt(normalizedDestination),
                        destinationHash,
                        keyHash,
                        requestFingerprint);
            } catch (DataIntegrityViolationException exception) {
                result = replayOrExisting(subject.accountId(), channel, destinationHash, keyHash, requestFingerprint);
            }
        } catch (RuntimeException exception) {
            auditService.record(
                    subject.accountId(),
                    subject.sessionId(),
                    "ENDPOINT_ENROLLMENT",
                    NotificationSecurityAuditOutcome.DENIED,
                    null,
                    auditReason(exception));
            throw exception;
        }
        // Keep the successful audit write outside the operation catch so an audit-store failure
        // cannot be misclassified as an enrollment denial or recursively audited.
        auditService.record(
                subject.accountId(),
                subject.sessionId(),
                "ENDPOINT_ENROLLMENT",
                NotificationSecurityAuditOutcome.SUCCESS,
                result.endpoint().getId(),
                result.duplicate() ? "IDEMPOTENT_REPLAY" : "ENROLLED");
        return result;
    }

    public List<NotificationEndpoint> list(NotificationSubject subject) {
        return endpointRepository.findByOwnerAccountIdOrderByCreatedAtAsc(subject.accountId());
    }

    /** User revocation is naturally idempotent and cannot affect an endpoint owned by another account. */
    public void revoke(NotificationSubject subject, UUID endpointId) {
        NotificationEndpoint endpoint;
        try {
            endpoint = endpointRepository.findByIdAndOwnerAccountId(endpointId, subject.accountId())
                    .orElseThrow(EndpointNotFoundException::new);
            if (endpoint.isEnabled()) {
                endpoint.disable("USER_DISABLED", clock.instant());
            }
        } catch (RuntimeException exception) {
            auditService.record(
                    subject.accountId(),
                    subject.sessionId(),
                    "ENDPOINT_REVOCATION",
                    NotificationSecurityAuditOutcome.DENIED,
                    endpointId,
                    auditReason(exception));
            throw exception;
        }
        auditService.record(
                subject.accountId(),
                subject.sessionId(),
                "ENDPOINT_REVOCATION",
                NotificationSecurityAuditOutcome.SUCCESS,
                endpoint.getId(),
                "REVOKED");
    }

    private EndpointRegistrationResult replayOrExisting(
            UUID ownerAccountId,
            NotificationChannel channel,
            String destinationHash,
            String keyHash,
            String requestFingerprint) {
        NotificationEndpointRegistrationIdempotency reservation = idempotencyRepository
                .findByOwnerAccountIdAndIdempotencyKeyHash(ownerAccountId, keyHash)
                .orElse(null);
        if (reservation == null) {
            NotificationEndpoint endpoint = endpointRepository
                    .findByOwnerAccountIdAndChannelAndDestinationHash(ownerAccountId, channel, destinationHash)
                    .orElseThrow(EndpointIdempotencyUnavailableException::new);
            return new EndpointRegistrationResult(endpoint, true);
        }
        if (!requestFingerprint.equals(reservation.getRequestFingerprint())) {
            throw new EndpointIdempotencyConflictException();
        }
        if (reservation.getState() != EndpointRegistrationIdempotencyState.COMPLETED) {
            throw new EndpointIdempotencyUnavailableException();
        }
        NotificationEndpoint endpoint = endpointRepository.findByIdAndOwnerAccountId(reservation.getEndpointId(), ownerAccountId)
                .orElseThrow(EndpointIdempotencyUnavailableException::new);
        return new EndpointRegistrationResult(endpoint, true);
    }

    private String digest(String domain, String value) {
        return SensitiveValueDigest.hmacSha256(properties.getIdempotencySecret(), domain, value);
    }

    private static String auditReason(RuntimeException exception) {
        if (exception instanceof EndpointIdempotencyConflictException) {
            return "IDEMPOTENCY_CONFLICT";
        }
        if (exception instanceof InvalidEndpointIdempotencyKeyException || exception instanceof IllegalArgumentException) {
            return "INVALID_REQUEST";
        }
        if (exception instanceof EndpointNotFoundException) {
            return "ENDPOINT_NOT_FOUND";
        }
        return "OPERATION_FAILED";
    }

    private static String normalize(NotificationChannel channel, String value) {
        if (value == null) {
            throw new IllegalArgumentException("endpoint destination is required");
        }
        String normalized = value.trim();
        return switch (channel) {
            case EMAIL -> normalizeEmail(normalized);
            case PUSH -> normalizePushToken(normalized);
            case REALTIME -> throw new IllegalArgumentException("realtime does not use endpoint destinations");
        };
    }

    private static String normalizeEmail(String value) {
        String normalized = value.toLowerCase(java.util.Locale.ROOT);
        if (normalized.length() > 320 || !normalized.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
            throw new IllegalArgumentException("email endpoint is not valid");
        }
        return normalized;
    }

    private static String normalizePushToken(String value) {
        if (value.length() < 16 || value.length() > 4_096 || !value.matches("[A-Za-z0-9_:.+/=\\-]+")) {
            throw new IllegalArgumentException("push endpoint is not valid");
        }
        return value;
    }
}
