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

    private final AuthorizationPolicy policy;

    /** Creates the default v1 policy for direct unit tests. */
    public DefaultAuthorizationPolicyRepository() {
        this("v1");
    }

    /**
     * Creates the exact initial rule table for a supplied version.
     *
     * @param policyVersion externally configured policy version
     */
    public DefaultAuthorizationPolicyRepository(String policyVersion) {
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

    @Override
    public AuthorizationPolicy loadCurrentPolicy() {
        return policy;
    }
}
