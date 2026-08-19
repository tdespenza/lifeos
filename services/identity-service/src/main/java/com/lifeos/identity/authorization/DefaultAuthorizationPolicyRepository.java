package com.lifeos.identity.authorization;

import com.lifeos.identity.auth.IdentityAuthProperties;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * In-process, versioned policy repository.
 *
 * <p>The class is a port adapter, not a permanent location for policy administration. Versioning
 * keeps callers from accidentally evaluating policy semantics they did not deploy with; a future
 * database or generated gRPC policy adapter can replace this component without changing decision
 * semantics.
 */
@Component
public class DefaultAuthorizationPolicyRepository implements AuthorizationPolicyRepository {

    /** Legacy protected-service policy version. */
    public static final String V1_POLICY_VERSION = "v1";

    /** Descriptor-registry policy version. */
    public static final String V2_POLICY_VERSION = "v2";

    private static final Set<AuthorizationRole> PERMITTED_ROLES =
            Set.of(AuthorizationRole.MEMBER, AuthorizationRole.TENANT_ADMIN);

    private final AuthorizationPolicy activePolicy;
    private final AuthorizationPolicy v1CompatibilityPolicy;

    /** Creates the default v1 policy for direct unit tests. */
    public DefaultAuthorizationPolicyRepository() {
        this(V1_POLICY_VERSION);
    }

    /**
     * Creates the exact initial rule table for a supplied version.
     *
     * @param policyVersion externally configured policy version
     */
    public DefaultAuthorizationPolicyRepository(String policyVersion) {
        if (!isSupportedPolicyVersion(policyVersion)) {
            throw new IllegalArgumentException("Unsupported authorization policy version");
        }
        this.v1CompatibilityPolicy = new AuthorizationPolicy(V1_POLICY_VERSION, v1Rules());
        this.activePolicy = V1_POLICY_VERSION.equals(policyVersion)
                ? v1CompatibilityPolicy
                : new AuthorizationPolicy(V2_POLICY_VERSION, v2Rules());
    }

    /**
     * Creates the production policy adapter using the externally configured version.
     *
     * @param properties identity authentication and authorization settings
     */
    @Autowired
    public DefaultAuthorizationPolicyRepository(IdentityAuthProperties properties) {
        this(properties.getAuthorization().getPolicyVersion());
    }

    /**
     * Indicates whether this deployed adapter has deterministic rules for a version.
     *
     * @param policyVersion configured version
     * @return {@code true} only for an implemented policy version
     */
    public static boolean isSupportedPolicyVersion(String policyVersion) {
        return V1_POLICY_VERSION.equals(policyVersion) || V2_POLICY_VERSION.equals(policyVersion);
    }

    private static Map<AuthorizationAction, Set<AuthorizationRole>> v1Rules() {
        Map<AuthorizationAction, Set<AuthorizationRole>> rules = new EnumMap<>(AuthorizationAction.class);
        // This is deliberately an explicit allow-list. Adding an enum value does not grant it
        // access until a reviewed policy version adds a rule for that exact action.
        addRules(rules,
                AuthorizationAction.GOAL_CREATE,
                AuthorizationAction.GOAL_LIST,
                AuthorizationAction.GOAL_READ,
                AuthorizationAction.GOAL_UPDATE,
                AuthorizationAction.GOAL_COMPLETE,
                AuthorizationAction.GOAL_ARCHIVE,
                AuthorizationAction.GOAL_DEPENDENCY_ORDER,
                AuthorizationAction.PROFILE_CREATE,
                AuthorizationAction.PROFILE_READ,
                AuthorizationAction.PROFILE_UPDATE,
                AuthorizationAction.PROFILE_PREFERENCES_READ,
                AuthorizationAction.PROFILE_PREFERENCES_UPDATE,
                AuthorizationAction.PROFILE_PRIVACY_READ,
                AuthorizationAction.PROFILE_PRIVACY_UPDATE,
                AuthorizationAction.PROFILE_AI_PERSONALIZATION_READ,
                AuthorizationAction.PROFILE_AI_PERSONALIZATION_UPDATE,
                AuthorizationAction.HOUSEHOLD_CREATE,
                AuthorizationAction.HOUSEHOLD_READ,
                AuthorizationAction.HOUSEHOLD_MEMBERS_READ,
                AuthorizationAction.HOUSEHOLD_MEMBERS_MANAGE);
        return rules;
    }

    private static Map<AuthorizationAction, Set<AuthorizationRole>> v2Rules() {
        Map<AuthorizationAction, Set<AuthorizationRole>> rules = v1Rules();
        // V2 remains explicit: newly added enum members have no effect until they appear here and
        // have an exact descriptor in AuthorizationActionDescriptorRegistry.
        addRules(rules,
                AuthorizationAction.TASK_CREATE,
                AuthorizationAction.TASK_LIST,
                AuthorizationAction.TASK_READ,
                AuthorizationAction.TASK_UPDATE,
                AuthorizationAction.TASK_COMPLETE,
                AuthorizationAction.TASK_CANCEL,
                AuthorizationAction.TASK_DEPENDENCY_MANAGE,
                AuthorizationAction.TASK_DEPENDENCY_ORDER,
                AuthorizationAction.HABIT_CREATE,
                AuthorizationAction.HABIT_LIST,
                AuthorizationAction.HABIT_READ,
                AuthorizationAction.HABIT_UPDATE,
                AuthorizationAction.HABIT_OCCURRENCE_CREATE,
                AuthorizationAction.HABIT_TREND_READ,
                AuthorizationAction.ROUTINE_CREATE,
                AuthorizationAction.ROUTINE_LIST,
                AuthorizationAction.ROUTINE_READ,
                AuthorizationAction.ROUTINE_UPDATE,
                AuthorizationAction.ROUTINE_MATERIALIZE,
                AuthorizationAction.MILESTONE_CREATE,
                AuthorizationAction.MILESTONE_LIST,
                AuthorizationAction.MILESTONE_READ,
                AuthorizationAction.MILESTONE_UPDATE,
                AuthorizationAction.MILESTONE_COMPLETE,
                AuthorizationAction.CALENDAR_EVENT_CREATE,
                AuthorizationAction.CALENDAR_EVENT_LIST,
                AuthorizationAction.CALENDAR_EVENT_READ,
                AuthorizationAction.CALENDAR_EVENT_UPDATE,
                AuthorizationAction.CALENDAR_EVENT_CANCEL,
                AuthorizationAction.CALENDAR_TIME_BLOCK_CREATE,
                AuthorizationAction.CALENDAR_TIME_BLOCK_LIST,
                AuthorizationAction.CALENDAR_TIME_BLOCK_READ,
                AuthorizationAction.CALENDAR_TIME_BLOCK_UPDATE,
                AuthorizationAction.CALENDAR_TIME_BLOCK_CANCEL,
                AuthorizationAction.CALENDAR_CONFLICT_READ,
                AuthorizationAction.CALENDAR_OPTIMIZE,
                AuthorizationAction.FINANCE_BUDGET_CREATE,
                AuthorizationAction.FINANCE_BUDGET_LIST,
                AuthorizationAction.FINANCE_BUDGET_READ,
                AuthorizationAction.FINANCE_BUDGET_UPDATE,
                AuthorizationAction.FINANCE_TRANSACTION_CREATE,
                AuthorizationAction.FINANCE_TRANSACTION_LIST,
                AuthorizationAction.FINANCE_TRANSACTION_READ,
                AuthorizationAction.FINANCE_TRANSACTION_CATEGORIZE,
                AuthorizationAction.FINANCE_INSIGHTS_READ,
                AuthorizationAction.FINANCE_FORECAST_READ,
                AuthorizationAction.FINANCE_GOAL_CREATE,
                AuthorizationAction.FINANCE_GOAL_LIST,
                AuthorizationAction.FINANCE_GOAL_READ,
                AuthorizationAction.FINANCE_GOAL_UPDATE,
                AuthorizationAction.FINANCE_GOAL_CONTRIBUTE,
                AuthorizationAction.TRUST_DOCUMENT_PROOF_CREATE,
                AuthorizationAction.TRUST_MERKLE_PROOF_CREATE,
                AuthorizationAction.TRUST_PROOF_VERIFY,
                AuthorizationAction.TRUST_ANCHOR_CREATE,
                AuthorizationAction.TRUST_CREDENTIAL_VERIFY,
                AuthorizationAction.TRUST_AI_AUDIT_ANCHOR_CREATE,
                AuthorizationAction.TRUST_GOAL_CERTIFICATE_CREATE,
                AuthorizationAction.TRUST_SESSION_SUMMARY_ANCHOR_CREATE,
                AuthorizationAction.DOCUMENT_CREATE,
                AuthorizationAction.DOCUMENT_READ,
                AuthorizationAction.DOCUMENT_UPDATE,
                AuthorizationAction.DOCUMENT_SEARCH,
                AuthorizationAction.DOCUMENT_PROOF_REQUEST,
                AuthorizationAction.MEDIA_ASSET_CREATE,
                AuthorizationAction.MEDIA_ASSET_LIST,
                AuthorizationAction.MEDIA_ASSET_READ,
                AuthorizationAction.MEDIA_ASSET_UPLOAD,
                AuthorizationAction.MEDIA_HLS_MANIFEST_READ,
                AuthorizationAction.MEDIA_HLS_SEGMENT_READ,
                AuthorizationAction.MEDIA_SESSION_CREATE,
                AuthorizationAction.MEDIA_SESSION_LIST,
                AuthorizationAction.MEDIA_SESSION_READ,
                AuthorizationAction.MEDIA_SESSION_UPDATE,
                AuthorizationAction.MEDIA_SESSION_CANCEL,
                AuthorizationAction.MEDIA_SESSION_JOIN,
                AuthorizationAction.ANALYTICS_DASHBOARD_READ);
        return rules;
    }

    private static void addRules(
            Map<AuthorizationAction, Set<AuthorizationRole>> rules, AuthorizationAction... actions) {
        for (AuthorizationAction action : actions) {
            rules.put(action, PERMITTED_ROLES);
        }
    }

    @Override
    public AuthorizationPolicy loadCurrentPolicy() {
        return activePolicy;
    }

    /**
     * Serves an immutable v1 compatibility view only for legacy v1 actions while v2 is active.
     *
     * <p>The response version remains {@code v1}; existing Task/Profile clients compare it
     * exactly and therefore remain safe during a rolling migration. New v2 actions never resolve
     * through this compatibility path.
     */
    @Override
    public Optional<AuthorizationPolicy> findCompatiblePolicy(
            String expectedPolicyVersion, AuthorizationAction action) {
        if (V2_POLICY_VERSION.equals(activePolicy.version())
                && V1_POLICY_VERSION.equals(expectedPolicyVersion)
                && v1CompatibilityPolicy.supports(action)) {
            return Optional.of(v1CompatibilityPolicy);
        }
        return Optional.empty();
    }
}
