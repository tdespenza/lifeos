package com.lifeos.identity.authorization;

import com.lifeos.identity.auth.IdentityAuthProperties;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
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
        Map<AuthorizationAction, java.util.Set<AuthorizationRole>> rules =
                new EnumMap<>(AuthorizationAction.class);
        java.util.Set<AuthorizationRole> allowed =
                EnumSet.of(AuthorizationRole.MEMBER, AuthorizationRole.TENANT_ADMIN);
        for (AuthorizationAction action : AuthorizationAction.values()) {
            rules.put(action, allowed);
        }
        this.policy = new AuthorizationPolicy(policyVersion, rules);
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

    @Override
    public AuthorizationPolicy loadCurrentPolicy() {
        return policy;
    }
}
