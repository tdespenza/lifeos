package com.lifeos.taskgoal.authorization;

/** Authentication and object-authorization boundary owned by the identity service. */
public interface TaskAccessService {

    /**
     * Validates one inbound Authorization header and returns its authenticated subject.
     *
     * @param authorizationHeader inbound HTTP Authorization header
     * @return trusted subject context
     * @throws TaskAuthenticationFailure when the bearer credential is not valid
     * @throws TaskAuthorizationDependencyUnavailable when identity cannot safely validate it
     */
    TaskSubject authenticate(String authorizationHeader);

    /**
     * Requires an allow decision for an already authenticated subject and trusted resource facts.
     *
     * @param subject validated subject
     * @param action canonical goal action
     * @param resource trusted resource facts
     * @throws TaskAuthorizationDenied when policy does not allow the action
     * @throws TaskAuthorizationDependencyUnavailable when identity cannot safely decide
     */
    void authorize(TaskSubject subject, String action, GoalAuthorizationResource resource);
}
