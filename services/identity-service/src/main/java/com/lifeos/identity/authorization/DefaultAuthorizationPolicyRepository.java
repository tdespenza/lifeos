package com.lifeos.identity.authorization;

import com.lifeos.identity.auth.IdentityAuthProperties;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Initial in-process policy repository.
 *
 * <p>The class is a port adapter, not a permanent location for policy administration. Versioning
 * keeps callers from accidentally evaluating policy semantics they did not deploy with; a future
 * database or generated gRPC policy adapter can replace this component without changing decision
 * semantics.
 */
@Component
public class DefaultAuthorizationPolicyRepository implements AuthorizationPolicyRepository {

    /** Only policy version implemented by this adapter. */
    public static final String SUPPORTED_POLICY_VERSION = "v1";
    private static final Set<AuthorizationRole> V1_PERMITTED_ROLES =
            Set.of(AuthorizationRole.MEMBER, AuthorizationRole.TENANT_ADMIN);

    private final AuthorizationPolicy policy;

    /** Creates the default v1 policy for direct unit tests. */
    public DefaultAuthorizationPolicyRepository() {
        this(SUPPORTED_POLICY_VERSION);
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
        this.policy = new AuthorizationPolicy(policyVersion, v1Rules());
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
        return SUPPORTED_POLICY_VERSION.equals(policyVersion);
    }

    private static Map<AuthorizationAction, Set<AuthorizationRole>> v1Rules() {
        Map<AuthorizationAction, Set<AuthorizationRole>> rules = new EnumMap<>(AuthorizationAction.class);
        // This is deliberately an explicit allow-list. Adding an enum value does not grant it
        // access until a reviewed policy version adds a rule for that exact action.
        rules.put(AuthorizationAction.GOAL_CREATE, V1_PERMITTED_ROLES);
        rules.put(AuthorizationAction.GOAL_LIST, V1_PERMITTED_ROLES);
        rules.put(AuthorizationAction.GOAL_READ, V1_PERMITTED_ROLES);
        rules.put(AuthorizationAction.GOAL_DEPENDENCY_ORDER, V1_PERMITTED_ROLES);
        return rules;
    }

    @Override
    public AuthorizationPolicy loadCurrentPolicy() {
        return policy;
    }
}
