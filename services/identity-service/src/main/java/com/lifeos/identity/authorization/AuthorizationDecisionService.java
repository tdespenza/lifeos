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
 * with a closed action descriptor and trusted resource facts supplied by an authenticated protected
 * service. It does not fetch domain resources, log resource data, or use a permissive cache.
 */
@Service
public class AuthorizationDecisionService {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationDecisionService.class);
    private static final String UNKNOWN_POLICY_VERSION = "unknown";
    private static final String OWNER_ACCOUNT_ID = "ownerAccountId";
    private static final String RESOURCE_EXISTS = "resourceExists";
    private static final String REQUESTER_ACCOUNT_ID = "requesterAccountId";
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
    private final AuthorizationActionDescriptorRegistry descriptorRegistry;
    private final Clock clock;

    /**
     * Creates the production authority.
     *
     * @param sessionRepository durable session/revocation authority
     * @param accountRepository active-account authority
     * @param membershipRepository scoped role membership repository
     * @param policyRepository active versioned policy source
     * @param descriptorRegistry exact workload/action/resource contracts
     */
    @Autowired
    public AuthorizationDecisionService(
            AuthSessionRepository sessionRepository,
            UserAccountRepository accountRepository,
            AuthorizationMembershipRepository membershipRepository,
            AuthorizationPolicyRepository policyRepository,
            AuthorizationActionDescriptorRegistry descriptorRegistry) {
        this(
                sessionRepository,
                accountRepository,
                membershipRepository,
                policyRepository,
                descriptorRegistry,
                Clock.systemUTC());
    }

    /**
     * Creates the authority with an injectable clock for deterministic decision-table tests.
     *
     * <p>This compatibility constructor retains the V1 in-process test seam. HTTP ingress always
     * uses {@link #decideForAudit(AuthorizationRequest, String)} and therefore supplies a proven
     * workload identity.
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
        this(
                sessionRepository,
                accountRepository,
                membershipRepository,
                policyRepository,
                new AuthorizationActionDescriptorRegistry(),
                clock);
    }

    /**
     * Creates the authority with explicit test seams for the descriptor table and clock.
     *
     * @param sessionRepository durable session/revocation authority
     * @param accountRepository active-account authority
     * @param membershipRepository scoped role membership repository
     * @param policyRepository active versioned policy source
     * @param descriptorRegistry exact workload/action/resource contracts
     * @param clock time source
     */
    AuthorizationDecisionService(
            AuthSessionRepository sessionRepository,
            UserAccountRepository accountRepository,
            AuthorizationMembershipRepository membershipRepository,
            AuthorizationPolicyRepository policyRepository,
            AuthorizationActionDescriptorRegistry descriptorRegistry,
            Clock clock) {
        this.sessionRepository = sessionRepository;
        this.accountRepository = accountRepository;
        this.membershipRepository = membershipRepository;
        this.policyRepository = policyRepository;
        this.descriptorRegistry = descriptorRegistry;
        this.clock = clock;
    }

    /**
     * Evaluates one in-process decision without throwing for ordinary denial.
     *
     * <p>The internal HTTP adapter must use the workload-aware overload. This method is retained
     * for existing in-process V1 policy consumers and deterministic decision-table tests; it is not
     * an authenticated transport ingress point.
     *
     * @param request request with trusted facts
     * @return deterministic allow or deny
     */
    @Transactional(readOnly = true)
    public AuthorizationDecision decide(AuthorizationRequest request) {
        return decideForAudit(request).decision();
    }

    /**
     * Evaluates a decision for a workload already authenticated by the transport boundary.
     *
     * @param request request with trusted facts
     * @param authenticatedWorkloadIdentity exact identity proven by the transport boundary
     * @return deterministic allow or deny
     */
    @Transactional(readOnly = true)
    public AuthorizationDecision decide(AuthorizationRequest request, String authenticatedWorkloadIdentity) {
        return decideForAudit(request, authenticatedWorkloadIdentity).decision();
    }

    /**
     * Evaluates an in-process decision and supplies internal-only durable-subject evidence for audit
     * writing.
     *
     * <p>This compatibility seam has no workload because it is not a transport ingress point. The
     * HTTP adapter uses the overload with a verified workload identity.
     *
     * @param request request with trusted facts
     * @return decision plus optional verified audit subject
     */
    @Transactional(readOnly = true)
    public AuthorizationDecisionEvaluation decideForAudit(AuthorizationRequest request) {
        return decideInternal(request, null, false);
    }

    /**
     * Evaluates an authenticated workload decision and supplies durable-subject evidence for audit
     * writing.
     *
     * <p>The returned subject is never serialized. It is absent unless the service has verified
     * session ownership, revocation, expiry, and account activity against durable state.
     *
     * @param request request from an authenticated protected-service adapter
     * @param authenticatedWorkloadIdentity exact workload identity proven before request binding
     * @return decision plus optional verified audit subject
     */
    @Transactional(readOnly = true)
    public AuthorizationDecisionEvaluation decideForAudit(
            AuthorizationRequest request, String authenticatedWorkloadIdentity) {
        return decideInternal(request, authenticatedWorkloadIdentity, true);
    }

    private AuthorizationDecisionEvaluation decideInternal(
            AuthorizationRequest request, String authenticatedWorkloadIdentity, boolean enforceWorkloadBinding) {
        Instant now = clock.instant();
        if (!isRequestShapeValid(request)) {
            return deny(AuthorizationDenyReason.MALFORMED_REQUEST, UNKNOWN_POLICY_VERSION, now);
        }

        Optional<AuthorizationAction> requestedAction = AuthorizationAction.fromValue(request.action());
        Optional<AuthorizationActionDescriptor> descriptor = requestedAction.flatMap(descriptorRegistry::find);
        if (requestedAction.isPresent()
                && (descriptor.isEmpty() || !isResourceShapeValid(request.resource(), descriptor.get()))) {
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
        AuthorizationPolicy activePolicy;
        AuthorizationPolicy policy;
        try {
            activePolicy = policyRepository.loadCurrentPolicy();
            if (activePolicy == null) {
                return deny(
                        AuthorizationDenyReason.POLICY_UNAVAILABLE,
                        UNKNOWN_POLICY_VERSION,
                        subjectState.expiresAt(),
                        verifiedSubjectId);
            }
            policy = resolvePolicy(activePolicy, request.expectedPolicyVersion(), requestedAction);
        } catch (RuntimeException ignored) {
            log.atWarn()
                    .addKeyValue("event", "authorization_policy_unavailable")
                    .log("Authorization policy lookup failed closed");
            return deny(
                    AuthorizationDenyReason.POLICY_UNAVAILABLE,
                    UNKNOWN_POLICY_VERSION,
                    subjectState.expiresAt(),
                    verifiedSubjectId);
        }

        if (policy == null || !policy.version().equals(request.expectedPolicyVersion())) {
            return deny(
                    AuthorizationDenyReason.POLICY_VERSION_MISMATCH,
                    activePolicy.version(),
                    subjectState.expiresAt(),
                    verifiedSubjectId);
        }

        if (requestedAction.isEmpty() || descriptor.isEmpty() || !policy.supports(requestedAction.get())) {
            return deny(
                    AuthorizationDenyReason.UNSUPPORTED_ACTION,
                    policy.version(),
                    subjectState.expiresAt(),
                    verifiedSubjectId);
        }

        if (enforceWorkloadBinding
                && !descriptor.get().workloadIdentity().equals(authenticatedWorkloadIdentity)) {
            return deny(
                    AuthorizationDenyReason.WORKLOAD_NOT_AUTHORIZED,
                    policy.version(),
                    subjectState.expiresAt(),
                    verifiedSubjectId);
        }

        Set<AuthorizationRole> roles;
        try {
            roles = effectiveRoles(verifiedSubjectId, request.resource().tenantId());
        } catch (RuntimeException ignored) {
            log.atWarn()
                    .addKeyValue("event", "authorization_membership_lookup_unavailable")
                    .log("Authorization membership lookup failed closed");
            return deny(
                    AuthorizationDenyReason.POLICY_UNAVAILABLE,
                    policy.version(),
                    subjectState.expiresAt(),
                    verifiedSubjectId);
        }
        if (roles.isEmpty()) {
            AuthorizationDenyReason reason = request.resource().tenantId().equals(verifiedSubjectId.toString())
                    ? AuthorizationDenyReason.MISSING_ROLE
                    : AuthorizationDenyReason.TENANT_MISMATCH;
            return deny(reason, policy.version(), subjectState.expiresAt(), verifiedSubjectId);
        }
        if (!policy.permits(requestedAction.get(), roles)) {
            return deny(
                    AuthorizationDenyReason.MISSING_ROLE,
                    policy.version(),
                    subjectState.expiresAt(),
                    verifiedSubjectId);
        }

        return evaluateDescriptor(
                request.resource(),
                descriptor.get(),
                roles,
                policy.version(),
                subjectState.expiresAt(),
                verifiedSubjectId);
    }

    private AuthorizationPolicy resolvePolicy(
            AuthorizationPolicy activePolicy,
            String expectedPolicyVersion,
            Optional<AuthorizationAction> requestedAction) {
        if (activePolicy.version().equals(expectedPolicyVersion)) {
            return activePolicy;
        }
        if (requestedAction.isEmpty()) {
            return null;
        }
        return policyRepository
                .findCompatiblePolicy(expectedPolicyVersion, requestedAction.get())
                .orElse(null);
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
        boolean accountActive = accountRepository
                .findById(request.subjectId())
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

    private AuthorizationDecisionEvaluation evaluateDescriptor(
            AuthorizationResource resource,
            AuthorizationActionDescriptor descriptor,
            Set<AuthorizationRole> roles,
            String policyVersion,
            Instant expiresAt,
            UUID verifiedSubjectId) {
        return switch (descriptor.resourceShape()) {
            case OWNED_CREATE -> evaluateOwnedCreate(
                    resource, descriptor, policyVersion, expiresAt, verifiedSubjectId);
            case OWNED_OBJECT -> evaluateOwnedObject(
                    resource, descriptor, roles, policyVersion, expiresAt, verifiedSubjectId);
            case TENANT_COLLECTION -> evaluateTenantCollection(
                    resource, descriptor, policyVersion, expiresAt, verifiedSubjectId);
            case REQUESTER_CAPABILITY -> evaluateRequesterCapability(
                    resource, descriptor, policyVersion, expiresAt, verifiedSubjectId);
        };
    }

    private AuthorizationDecisionEvaluation evaluateOwnedCreate(
            AuthorizationResource resource,
            AuthorizationActionDescriptor descriptor,
            String policyVersion,
            Instant expiresAt,
            UUID verifiedSubjectId) {
        UUID owner = parseOwner(resource.attributes());
        if (owner == null) {
            return deny(AuthorizationDenyReason.MALFORMED_REQUEST, policyVersion, expiresAt, verifiedSubjectId);
        }
        AuthorizationDenyReason tenantFailure = tenantFailure(resource, descriptor, verifiedSubjectId);
        if (tenantFailure != null) {
            return deny(tenantFailure, policyVersion, expiresAt, verifiedSubjectId);
        }
        return owner.equals(verifiedSubjectId)
                ? allow(policyVersion, expiresAt, verifiedSubjectId)
                : deny(AuthorizationDenyReason.OWNER_MISMATCH, policyVersion, expiresAt, verifiedSubjectId);
    }

    private AuthorizationDecisionEvaluation evaluateOwnedObject(
            AuthorizationResource resource,
            AuthorizationActionDescriptor descriptor,
            Set<AuthorizationRole> roles,
            String policyVersion,
            Instant expiresAt,
            UUID verifiedSubjectId) {
        if (!"true".equals(resource.attributes().get(RESOURCE_EXISTS))) {
            // Use the same bounded owner mismatch as a cross-user read. The protected service can
            // audit and deny an absent resource without teaching a caller or audit consumer whether
            // the object existed.
            return deny(AuthorizationDenyReason.OWNER_MISMATCH, policyVersion, expiresAt, verifiedSubjectId);
        }
        UUID owner = parseOwner(resource.attributes());
        if (owner == null) {
            return deny(AuthorizationDenyReason.MALFORMED_REQUEST, policyVersion, expiresAt, verifiedSubjectId);
        }
        AuthorizationDenyReason tenantFailure = tenantFailure(resource, descriptor, verifiedSubjectId);
        if (tenantFailure != null) {
            return deny(tenantFailure, policyVersion, expiresAt, verifiedSubjectId);
        }
        if (owner.equals(verifiedSubjectId)) {
            return allow(policyVersion, expiresAt, verifiedSubjectId);
        }
        if (descriptor.ownerRule() == AuthorizationOwnerRule.SUBJECT_OR_TENANT_ADMIN
                && roles.contains(AuthorizationRole.TENANT_ADMIN)) {
            return allow(policyVersion, expiresAt, verifiedSubjectId);
        }
        return deny(AuthorizationDenyReason.OWNER_MISMATCH, policyVersion, expiresAt, verifiedSubjectId);
    }

    private AuthorizationDecisionEvaluation evaluateTenantCollection(
            AuthorizationResource resource,
            AuthorizationActionDescriptor descriptor,
            String policyVersion,
            Instant expiresAt,
            UUID verifiedSubjectId) {
        AuthorizationDenyReason tenantFailure = tenantFailure(resource, descriptor, verifiedSubjectId);
        return tenantFailure == null
                ? allow(policyVersion, expiresAt, verifiedSubjectId)
                : deny(tenantFailure, policyVersion, expiresAt, verifiedSubjectId);
    }

    private AuthorizationDecisionEvaluation evaluateRequesterCapability(
            AuthorizationResource resource,
            AuthorizationActionDescriptor descriptor,
            String policyVersion,
            Instant expiresAt,
            UUID verifiedSubjectId) {
        UUID requester = parseRequester(resource.attributes());
        if (requester == null) {
            return deny(AuthorizationDenyReason.MALFORMED_REQUEST, policyVersion, expiresAt, verifiedSubjectId);
        }
        AuthorizationDenyReason tenantFailure = tenantFailure(resource, descriptor, verifiedSubjectId);
        if (tenantFailure != null) {
            return deny(tenantFailure, policyVersion, expiresAt, verifiedSubjectId);
        }
        // Identity establishes subject/session/policy capability only. Profile-service owns the
        // household relation and rechecks immutable owner tenancy plus the member's explicit local
        // permission under its resource lock; this avoids pretending Identity stores those facts.
        return requester.equals(verifiedSubjectId)
                ? allow(policyVersion, expiresAt, verifiedSubjectId)
                : deny(AuthorizationDenyReason.OWNER_MISMATCH, policyVersion, expiresAt, verifiedSubjectId);
    }

    private AuthorizationDenyReason tenantFailure(
            AuthorizationResource resource, AuthorizationActionDescriptor descriptor, UUID verifiedSubjectId) {
        return descriptor.tenantScope() == AuthorizationTenantScope.PERSONAL
                        && !resource.tenantId().equals(verifiedSubjectId.toString())
                ? AuthorizationDenyReason.TENANT_MISMATCH
                : null;
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

    private boolean isResourceShapeValid(
            AuthorizationResource resource, AuthorizationActionDescriptor descriptor) {
        if (!hasBoundedText(resource.tenantId(), AuthorizationMembership.MAX_TENANT_ID_LENGTH)
                || resource.attributes() == null
                || resource.attributes().size() > MAX_ATTRIBUTES
                || !boundedAttributes(resource.attributes())
                || !descriptor.resourceType().equals(resource.resourceType())) {
            return false;
        }
        return switch (descriptor.resourceShape()) {
            case OWNED_CREATE -> isUuid(resource.resourceId()) && hasOnlyOwnerAttribute(resource.attributes());
            case OWNED_OBJECT -> isUuid(resource.resourceId()) && hasOwnerAndExistenceAttributes(resource.attributes());
            case TENANT_COLLECTION -> resource.resourceId() == null && resource.attributes().isEmpty();
            case REQUESTER_CAPABILITY -> isUuid(resource.resourceId()) && hasOnlyRequesterAttribute(resource.attributes());
        };
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

    private boolean hasOnlyRequesterAttribute(Map<String, String> attributes) {
        return attributes.size() == 1 && isUuid(attributes.get(REQUESTER_ACCOUNT_ID));
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

    private UUID parseRequester(Map<String, String> attributes) {
        try {
            return UUID.fromString(attributes.get(REQUESTER_ACCOUNT_ID));
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
            AuthorizationDenyReason reason, String policyVersion, Instant expiresAt) {
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
