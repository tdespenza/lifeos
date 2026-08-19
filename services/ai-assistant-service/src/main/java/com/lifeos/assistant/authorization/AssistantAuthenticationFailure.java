package com.lifeos.assistant.authorization;

/** The caller did not present a bearer credential that Identity accepted. */
public class AssistantAuthenticationFailure extends RuntimeException {

    public AssistantAuthenticationFailure() {
        super();
    }

    public AssistantAuthenticationFailure(Throwable cause) {
        super(cause);
    }
}
