package com.lifeos.assistant.audit;

/** Fails a security-relevant assistant decision closed when it cannot be durably audited. */
public class AssistantAuditUnavailableException extends RuntimeException {

    public AssistantAuditUnavailableException(Throwable cause) {
        super(cause);
    }
}
