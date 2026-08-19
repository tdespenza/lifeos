package com.lifeos.assistant.tool;

/** The assistant confirmation ledger could not durably reserve or read a key. */
public class AssistantToolConfirmationUnavailableException extends RuntimeException {

    public AssistantToolConfirmationUnavailableException(Throwable cause) {
        super(cause);
    }
}
