package com.lifeos.identity.authorization;

import java.util.Arrays;
import java.util.Optional;

/** Exact action values supported by the versioned authorization policy. */
public enum AuthorizationAction {

    /** Create a personal goal. */
    GOAL_CREATE("goal:create"),

    /** List the caller's personal goal collection. */
    GOAL_LIST("goal:list"),

    /** Read one persisted goal. */
    GOAL_READ("goal:read"),

    /** Run dependency ordering within the caller's goal scope. */
    GOAL_DEPENDENCY_ORDER("goal:dependency-order");

    private final String value;

    AuthorizationAction(String value) {
        this.value = value;
    }

    /**
     * Returns the stable wire value used by protected services.
     *
     * @return exact action identifier
     */
    public String value() {
        return value;
    }

    /**
     * Resolves an exact policy action without case folding or aliasing.
     *
     * @param value untrusted action value
     * @return matching action when supported
     */
    public static Optional<AuthorizationAction> fromValue(String value) {
        return Arrays.stream(values()).filter(action -> action.value.equals(value)).findFirst();
    }
}
