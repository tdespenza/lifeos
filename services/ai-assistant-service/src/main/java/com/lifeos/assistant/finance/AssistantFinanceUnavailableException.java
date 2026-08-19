package com.lifeos.assistant.finance;

/** Finance aggregate read failed or was not configured. */
public class AssistantFinanceUnavailableException extends RuntimeException {

    public AssistantFinanceUnavailableException() {
        super("Finance insights are temporarily unavailable");
    }

    public AssistantFinanceUnavailableException(Throwable cause) {
        super("Finance insights are temporarily unavailable", cause);
    }
}
