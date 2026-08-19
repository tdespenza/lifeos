package com.lifeos.assistant.tool;

/** Explicit non-mutating tool result; this module does not invoke other services. */
public record AssistantToolPlan(
        AssistantToolOperation operation, String executionState, boolean requiresUserConfirmation, String reason) {
}
