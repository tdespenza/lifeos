package com.lifeos.assistant.authorization;

/** Identity validation is unavailable or returned an unsafe response, so access fails closed. */
public class AssistantIdentityDependencyUnavailable extends RuntimeException {

    public AssistantIdentityDependencyUnavailable() {
        super();
    }

    public AssistantIdentityDependencyUnavailable(Throwable cause) {
        super(cause);
    }
}
