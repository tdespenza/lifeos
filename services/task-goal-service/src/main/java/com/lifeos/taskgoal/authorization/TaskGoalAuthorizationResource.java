package com.lifeos.taskgoal.authorization;

import java.util.Map;

/**
 * Locally derived, policy-relevant facts sent to the identity decision authority.
 *
 * <p>Implementations must never be constructed from public request fields. Keeping this small
 * interface at the task-goal boundary lets Goal and Task resources retain their own strict shape
 * validation while sharing the same fail-closed transport adapter.
 */
public interface TaskGoalAuthorizationResource {

    String resourceType();

    String resourceId();

    String tenantId();

    Map<String, String> attributes();
}
