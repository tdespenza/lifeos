package com.lifeos.assistant.tool;

import java.util.Map;
import org.springframework.stereotype.Service;

/** Deterministic allow-list with no reflection, URL dispatch, shelling out, or remote mutation. */
@Service
public class AssistantToolPolicy {

    private static final Map<String, AssistantToolOperation> ALLOWED_OPERATIONS = Map.of(
            "NONE", AssistantToolOperation.NONE,
            "DRAFT_TASK", AssistantToolOperation.DRAFT_TASK,
            "DRAFT_GOAL", AssistantToolOperation.DRAFT_GOAL,
            "DRAFT_FINANCIAL_NOTE", AssistantToolOperation.DRAFT_FINANCIAL_NOTE);

    public AssistantToolOperation resolve(String requestedOperation) {
        if (requestedOperation == null || requestedOperation.isBlank()) {
            return AssistantToolOperation.NONE;
        }
        AssistantToolOperation operation = ALLOWED_OPERATIONS.get(requestedOperation);
        if (operation == null) {
            throw new AssistantToolOperationNotAllowedException();
        }
        return operation;
    }

    public AssistantToolPlan notExecuted(AssistantToolOperation operation, String reason) {
        if (operation == AssistantToolOperation.NONE) {
            return new AssistantToolPlan(operation, "NOT_REQUESTED", false, "NO_TOOL_REQUESTED");
        }
        return new AssistantToolPlan(operation, "NOT_EXECUTED", true, reason);
    }
}
