package com.lifeos.assistant.safety;

import java.util.Set;

/** Redacted prompt handling result; its diagnostic representation never includes user text. */
public record AssistantPromptSafetyAssessment(
        String providerPrompt,
        int inputCharacters,
        int estimatedInputTokens,
        Set<AssistantSafetyFlag> flags,
        AssistantPromptDisposition disposition) {

    public AssistantPromptSafetyAssessment {
        flags = Set.copyOf(flags);
    }

    @Override
    public String toString() {
        return "AssistantPromptSafetyAssessment[redacted]";
    }
}
