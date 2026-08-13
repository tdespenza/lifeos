package com.lifeos.identity.authorization;

import com.lifeos.identity.account.UserAccountRepository;
import com.lifeos.identity.auth.AuthSession;
import com.lifeos.identity.auth.AuthSessionRepository;
import com.lifeos.identity.auth.TokenDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deterministic RBAC + ABAC decision authority.
 *
 * <p>The service is deliberately deny-by-default. It revalidates durable session and account state
 * rather than trusting a bearer token's historical claims, then combines tenant-scoped RBAC roles
 * with explicit owner and tenant attributes supplied by a protected service. It does not fetch
 * domain resources, log resource data, or use a permissive cache.
 */
@Service
public class AuthorizationDecisionService {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationDecisionService.class);
    private static final String UNKNOWN_POLICY_VERSION = "unknown";
    private static final String GOAL_RESOURCE_TYPE = "goal";
    private static final String OWNER_ACCOUNT_ID = "ownerAccountId";
    private static final String RESOURCE_EXISTS = "resourceExists";
    private static final int MAX_ACTION_LENGTH = 64;
    private static final int MAX_POLICY_VERSION_LENGTH = 64;
    private static final int MAX_RESOURCE_ID_LENGTH = 128;
    private static final int MAX_ATTRIBUTES = 16;
    private static final int MAX_ATTRIBUTE_KEY_LENGTH = 64;
    private static final int MAX_ATTRIBUTE_VALUE_LENGTH = 256;

    private final AuthSessionRepository sessionRepository;
    private final UserAccountRepository accountRepository;
    private final AuthorizationMembershipRepository membershipRepository;
    private final AuthorizationPolicyRepository policyRepository;
    private final Clock clock;

    /**
     * Creates the production authority.
     *
     * @param sessionRepository durable session/revocation authority
     * @param accountRepository active-account authority
     * @param membershipRepository scoped role membership repository
     * @param policyRepository active versioned policy source
     */
    @Autowired
    public AuthorizationDecisionService(
            AuthSessionRepository sessionRepository,
            UserAccountRepository accountRepository,
            AuthorizationMembershipRepository membershipRepository,
            AuthorizationPolicyRepository policyRepository) {
        this(sessionRepository, accountRepository, membershipRepository, policyRepository, Clock.systemUTC());
    }

    /**
     * Creates the authority with an injectable clock for deterministic decision-table tests.
     *
     * @param sessionRepository durable session/revocation authority
     * @param accountRepository active-account authority
     * @param membershipRepository scoped role membership repository
     * @param policyRepository active versioned policy source
     * @param clock time source
     */
    AuthorizationDecisionService(
            AuthSessionRepository sessionRepository,
            UserAccountRepository accountRepository,
            AuthorizationMembershipRepository membershipRepository,
            AuthorizationPolicyRepository policyRepository,
            Clock clock) {
        this.sessionRepository = sessionRepository;
        this.accountRepository = accountRepository;
        this.membershipRepository = membershipRepository;
        this.policyRepository = policyRepository;
        this.clock = clock;
    }

    /**
     * Evaluates one decision without throwing for ordinary denial.
     *
     * <p>Precedence is stable: malformed request, stale subject, policy version, unsupported
     * action, scoped role, then ABAC tenant/owner conditions. Persistence and policy failures are
     * returned as {@link AuthorizationDenyReason#POLICY_UNAVAILABLE}; callers must not convert this
     * outcome to an allow.
     *
     * @param request request from an authenticated protected-service adapter
     * @return deterministic allow or deny
     */
    @Transactional(readOnly = true)
    public AuthorizationDecision decide(AuthorizationRequest request) {
        return decideForAudit(request).decision();
    }

    /**
     * Evaluates a decision and supplies internal-only durable-subject evidence for audit writing.
     *
     * <p>The returned subject is never serialized. It is absent unless the service has verified
     * session ownership, revocation, expiry, and account activity against durable state.
     *
     * @param request request from an authenticated protected-service adapter
     * @return decision plus optional verified audit subject
     */
    @Transactional(readOnly = true)
    public AuthorizationDecisionEvaluation decideForAudit(AuthorizationRequest request) {
        Instant now = clock.instant();
        if (!isRequestShapeValid(request)) {
            return deny(AuthorizationDenyReason.MALFORMED_REQUEST, UNKNOWN_POLICY_VERSION, now);
        }
        Optional<AuthorizationAction> requestedAction = AuthorizationAction.fromValue(request.action());
        if (requestedAction.isPresent()
                && !isResourceShapeValidForAction(request.resource(), requestedAction.get())) {
            return deny(AuthorizationDenyReason.MALFORMED_REQUEST, UNKNOWN_POLICY_VERSION, now);
        }

        SubjectState subjectState;
        try {
            subjectState = loadActiveSubject(request, now);
        } catch (RuntimeException ignored) {
            log.atWarn()
                    .addKeyValue("event", "authorization_subject_lookup_unavailable")
                    .log("Authorization subject verification failed closed");
            return deny(AuthorizationDenyReason.POLICY_UNAVAILABLE, UNKNOWN_POLICY_VERSION, now);
        }
        if (subjectState == null) {
            return deny(AuthorizationDenyReason.STALE_SUBJECT, UNKNOWN_POLICY_VERSION, now);
        }

        UUID verifiedSubjectId = subjectState.accountId();
        AuthorizationPolicy policy;
        try {
            policy = policyRepository.loadCurrentPolicy();
            if (policy == null) {
                return deny(AuthorizationDenyReason.POLICY_UNAVAILABLE, UNKNOWN_POLICY_VERSION,
                        subjectState.expiresAt(), verifiedSubjectId);
            }
        } catch (RuntimeException ignored) {
            log.atWarn()
                    .addKeyValue("event", "authorization_policy_unavailable")
                    .log("Authorization policy lookup failed closed");
            return deny(AuthorizationDenyReason.POLICY_UNAVAILABLE, UNKNOWN_POLICY_VERSION,
                    subjectState.expiresAt(), verifiedSubjectId);
        }

        if (!policy.version().equals(request.expectedPolicyVersion())) {
            return deny(AuthorizationDenyReason.POLICY_VERSION_MISMATCH, policy.version(),
                    subjectState.expiresAt(), verifiedSubjectId);
        }

        if (requestedAction.isEmpty()) {
            return deny(AuthorizationDenyReason.UNSUPPORTED_ACTION, policy.version(),
                    subjectState.expiresAt(), verifiedSubjectId);
        }

        Set<AuthorizationRole> roles;
        try {
            roles = effectiveRoles(verifiedSubjectId, request.resource().tenantId());
        } catch (RuntimeException ignored) {
            log.atWarn()
                    .addKeyValue("event", "authorization_membership_lookup_unavailable")
                    .log("Authorization membership lookup failed closed");
            return deny(AuthorizationDenyReason.POLICY_UNAVAILABLE, policy.version(),
                    subjectState.expiresAt(), verifiedSubjectId);
        }
        if (roles.isEmpty()) {
            AuthorizationDenyReason reason = request.resource().tenantId().equals(verifiedSubjectId.toString())
                    ? AuthorizationDenyReason.MISSING_ROLE
                    : AuthorizationDenyReason.TENANT_MISMATCH;
            return deny(reason, policy.version(), subjectState.expiresAt(), verifiedSubjectId);
        }
        if (!policy.permits(requestedAction.get(), roles)) {
            return deny(AuthorizationDenyReason.MISSING_ROLE, policy.version(),
                    subjectState.expiresAt(), verifiedSubjectId);
        }

        return evaluateAttributes(
                request,
                requestedAction.get(),
                roles,
                policy.version(),
                subjectState.expiresAt(),
                verifiedSubjectId);
    }

    private SubjectState loadActiveSubject(AuthorizationRequest request, Instant now) {
        Optional<AuthSession> session = sessionRepository.findById(request.sessionId());
        if (session.isEmpty()
                || !session.get().getAccountId().equals(request.subjectId())
                || session.get().isRevoked()
                || !session.get().getExpiresAt().isAfter(now)
                || !TokenDigest.matches(session.get().getAccessTokenHash(), request.accessTokenProof())) {
            return null;
        }
        boolean accountActive = accountRepository.findById(request.subjectId())
                .map(account -> account.isActive())
                .orElse(false);
        return accountActive ? new SubjectState(request.subjectId(), session.get().getExpiresAt()) : null;
    }

    private Set<AuthorizationRole> effectiveRoles(UUID subjectId, String tenantId) {
        Set<AuthorizationRole> roles = EnumSet.noneOf(AuthorizationRole.class);
        if (tenantId.equals(subjectId.toString())) {
            roles.add(AuthorizationRole.MEMBER);
        }
        List<AuthorizationMembership> memberships =
                membershipRepository.findByAccountIdAndTenantIdAndActiveTrue(subjectId, tenantId);
        if (memberships != null) {
            memberships.stream()
                    .filter(membership -> membership.getRole() != null)
                    .map(AuthorizationMembership::getRole)
                    .forEach(roles::add);
        }
        return roles;
    }

    private AuthorizationDecisionEvaluation evaluateAttributes(
            AuthorizationRequest request,
            AuthorizationAction action,
            Set<AuthorizationRole> roles,
            String policyVersion,
            Instant expiresAt,
            UUID verifiedSubjectId) {
        AuthorizationResource resource = request.resource();

        return switch (action) {
            case GOAL_CREATE -> {
                UUID owner = parseOwner(resource.attributes());
                if (owner == null) {
                    yield deny(AuthorizationDenyReason.MALFORMED_REQUEST, policyVersion, expiresAt, verifiedSubjectId);
                }
                if (!resource.tenantId().equals(verifiedSubjectId.toString())) {
                    yield deny(AuthorizationDenyReason.TENANT_MISMATCH, policyVersion, expiresAt, verifiedSubjectId);
                }
                yield owner.equals(verifiedSubjectId)
                        ? allow(policyVersion, expiresAt, verifiedSubjectId)
                        : deny(AuthorizationDenyReason.OWNER_MISMATCH, policyVersion, expiresAt, verifiedSubjectId);
            }
            case GOAL_LIST, GOAL_DEPENDENCY_ORDER -> allow(policyVersion, expiresAt, verifiedSubjectId);
            case GOAL_READ -> {
                if (!"true".equals(resource.attributes().get(RESOURCE_EXISTS))) {
                    // Use the same bounded owner mismatch as a cross-user read. The protected
                    // service can therefore audit and deny an absent resource without teaching
                    // a caller or an audit consumer whether the object existed.
                    yield deny(AuthorizationDenyReason.OWNER_MISMATCH, policyVersion, expiresAt, verifiedSubjectId);
                }
                UUID owner = parseOwner(resource.attributes());
                if (owner == null) {
                    yield deny(AuthorizationDenyReason.MALFORMED_REQUEST, policyVersion, expiresAt, verifiedSubjectId);
                }
                if (owner.equals(verifiedSubjectId) || roles.contains(AuthorizationRole.TENANT_ADMIN)) {
                    yield allow(policyVersion, expiresAt, verifiedSubjectId);
                }
                yield deny(AuthorizationDenyReason.OWNER_MISMATCH, policyVersion, expiresAt, verifiedSubjectId);
            }
        };
    }

    private boolean isRequestShapeValid(AuthorizationRequest request) {
        return request != null
                && request.subjectId() != null
                && request.sessionId() != null
                && TokenDigest.isSha256Hex(request.accessTokenProof())
                && hasBoundedText(request.action(), MAX_ACTION_LENGTH)
                && hasBoundedText(request.expectedPolicyVersion(), MAX_POLICY_VERSION_LENGTH)
                && request.resource() != null;
    }

    private boolean isResourceShapeValidForAction(AuthorizationResource resource, AuthorizationAction action) {
        if (!GOAL_RESOURCE_TYPE.equals(resource.resourceType())
                || !hasBoundedText(resource.tenantId(), AuthorizationMembership.MAX_TENANT_ID_LENGTH)
                || resource.attributes() == null
                || resource.attributes().size() > MAX_ATTRIBUTES
                || !boundedAttributes(resource.attributes())) {
            return false;
        }
        if (action == AuthorizationAction.GOAL_CREATE || action == AuthorizationAction.GOAL_READ) {
            return isUuid(resource.resourceId()) && (action == AuthorizationAction.GOAL_CREATE
                    ? hasOnlyOwnerAttribute(resource.attributes())
                    : hasOwnerAndExistenceAttributes(resource.attributes()));
        }
        return resource.resourceId() == null && resource.attributes().isEmpty();
    }

    private boolean isUuid(String value) {
        if (!hasBoundedText(value, MAX_RESOURCE_ID_LENGTH)) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private boolean hasOnlyOwnerAttribute(Map<String, String> attributes) {
        return attributes.size() == 1 && isUuid(attributes.get(OWNER_ACCOUNT_ID));
    }

    private boolean hasOwnerAndExistenceAttributes(Map<String, String> attributes) {
        return attributes.size() == 2
                && isUuid(attributes.get(OWNER_ACCOUNT_ID))
                && ("true".equals(attributes.get(RESOURCE_EXISTS))
                || "false".equals(attributes.get(RESOURCE_EXISTS)));
    }

    private boolean boundedAttributes(Map<String, String> attributes) {
        for (Map.Entry<String, String> attribute : attributes.entrySet()) {
            if (!hasBoundedText(attribute.getKey(), MAX_ATTRIBUTE_KEY_LENGTH)
                    || !hasBoundedText(attribute.getValue(), MAX_ATTRIBUTE_VALUE_LENGTH)) {
                return false;
            }
        }
        return true;
    }

    private UUID parseOwner(Map<String, String> attributes) {
        try {
            return UUID.fromString(attributes.get(OWNER_ACCOUNT_ID));
        } catch (IllegalArgumentException | NullPointerException exception) {
            return null;
        }
    }

    private boolean hasBoundedText(String value, int maxLength) {
        return value != null && !value.isBlank() && value.length() <= maxLength;
    }

    private AuthorizationDecisionEvaluation allow(String policyVersion, Instant expiresAt, UUID verifiedSubjectId) {
        return new AuthorizationDecisionEvaluation(
                AuthorizationDecision.allow(policyVersion, expiresAt), verifiedSubjectId);
    }

    private AuthorizationDecisionEvaluation deny(
            AuthorizationDenyReason reason,
            String policyVersion,
            Instant expiresAt) {
        return deny(reason, policyVersion, expiresAt, null);
    }

    private AuthorizationDecisionEvaluation deny(
            AuthorizationDenyReason reason,
            String policyVersion,
            Instant expiresAt,
            UUID verifiedSubjectId) {
        return new AuthorizationDecisionEvaluation(
                AuthorizationDecision.deny(reason, policyVersion, expiresAt), verifiedSubjectId);
    }

    private record SubjectState(UUID accountId, Instant expiresAt) {
    }
}
