package com.lifeos.assistant.safety;

/** Bounded safety classifications attached to an assistant request without retaining its text. */
public enum AssistantSafetyFlag {
    PII_REDACTED,
    PROMPT_INJECTION_SUSPECTED,
    INPUT_CHARACTER_LIMIT_EXCEEDED,
    INPUT_TOKEN_LIMIT_EXCEEDED
}
