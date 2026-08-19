package com.lifeos.assistant.safety;

/** The only safety outcomes that may reach the provider boundary. */
public enum AssistantPromptDisposition {
    SAFE,
    REJECT_PROMPT_INJECTION,
    REJECT_INPUT_LIMIT
}
