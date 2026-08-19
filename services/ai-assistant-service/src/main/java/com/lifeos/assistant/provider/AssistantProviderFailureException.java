package com.lifeos.assistant.provider;

/** A configured provider failed or returned malformed output. */
public class AssistantProviderFailureException extends RuntimeException {

    public AssistantProviderFailureException(Throwable cause) {
        super(cause);
    }
}
