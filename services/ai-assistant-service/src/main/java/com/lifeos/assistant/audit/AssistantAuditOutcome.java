package com.lifeos.assistant.audit;

/** Client-safe result classes stored without prompt or response content. */
public enum AssistantAuditOutcome {
    ALLOWED,
    NOT_FOUND,
    PROMPT_REJECTED,
    OUTPUT_LIMIT_REJECTED,
    TOOL_REJECTED,
    PROVIDER_NOT_CONFIGURED,
    PROVIDER_TIMEOUT,
    PROVIDER_FAILED,
    AUTHENTICATION_FAILED,
    IDENTITY_UNAVAILABLE,
    TOOL_EXECUTED,
    TOOL_FAILED
}
