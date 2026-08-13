package com.lifeos.taskgoal.authorization;

/** Canonical authorization actions understood by the identity policy authority. */
public final class GoalAuthorizationActions {

    public static final String CREATE = "goal:create";
    public static final String LIST = "goal:list";
    public static final String READ = "goal:read";
    public static final String DEPENDENCY_ORDER = "goal:dependency-order";

    private GoalAuthorizationActions() {
    }
}
