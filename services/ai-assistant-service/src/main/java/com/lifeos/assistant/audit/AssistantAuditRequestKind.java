package com.lifeos.assistant.audit;

/** Bounded classifications for audited assistant API decisions. */
public enum AssistantAuditRequestKind {
    AUTHENTICATION,
    CONVERSATION_CREATE,
    CONVERSATION_READ,
    GENERATION_REQUEST,
    TOOL_EXECUTION
}
