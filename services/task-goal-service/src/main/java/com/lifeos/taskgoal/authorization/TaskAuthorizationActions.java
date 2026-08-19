package com.lifeos.taskgoal.authorization;

/** Exact v2 task action identifiers understood by the identity policy authority. */
public final class TaskAuthorizationActions {

    public static final String CREATE = "task:create";
    public static final String LIST = "task:list";
    public static final String READ = "task:read";
    public static final String UPDATE = "task:update";
    public static final String COMPLETE = "task:complete";
    public static final String CANCEL = "task:cancel";
    public static final String DEPENDENCY_MANAGE = "task:dependency-manage";
    public static final String DEPENDENCY_ORDER = "task:dependency-order";

    private TaskAuthorizationActions() {
    }
}
