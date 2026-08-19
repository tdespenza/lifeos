package com.lifeos.identity.authorization;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable, versioned RBAC rule table.
 *
 * <p>ABAC constraints remain explicit in the closed action descriptors, where the trusted
 * resource facts and subject/session scope are available.
 *
 * @param version stable policy version
 * @param allowedRoles roles allowed for each exact action
 */
public record AuthorizationPolicy(String version, Map<AuthorizationAction, Set<AuthorizationRole>> allowedRoles) {

    /**
     * Creates an immutable policy snapshot.
     */
    public AuthorizationPolicy {
        if (version == null || version.isBlank() || version.length() > 64) {
            throw new IllegalArgumentException("policy version must be between 1 and 64 characters");
        }
        Objects.requireNonNull(allowedRoles, "allowedRoles must not be null");
        Map<AuthorizationAction, Set<AuthorizationRole>> copy = new EnumMap<>(AuthorizationAction.class);
        allowedRoles.forEach((action, roles) -> {
            if (action == null || roles == null || roles.isEmpty()) {
                throw new IllegalArgumentException("policy actions must have at least one role");
            }
            copy.put(action, Collections.unmodifiableSet(EnumSet.copyOf(roles)));
        });
        allowedRoles = Collections.unmodifiableMap(copy);
    }

    /**
     * Returns whether any supplied role may perform the exact action.
     *
     * @param action exact action
     * @param roles effective tenant-scoped roles
     * @return {@code true} when policy permits at least one role
     */
    public boolean permits(AuthorizationAction action, Set<AuthorizationRole> roles) {
        Set<AuthorizationRole> required = allowedRoles.get(action);
        return required != null && roles.stream().anyMatch(required::contains);
    }

    /**
     * Returns whether this version has a reviewed rule for the exact action.
     *
     * @param action exact closed action
     * @return {@code true} only when the policy explicitly contains the action
     */
    public boolean supports(AuthorizationAction action) {
        return allowedRoles.containsKey(action);
    }
}
