package com.lifeos.assistant.tool;

import com.lifeos.assistant.audit.AssistantAuditOutcome;
import com.lifeos.assistant.audit.AssistantAuditRecord;
import com.lifeos.assistant.audit.AssistantAuditRequestKind;
import com.lifeos.assistant.audit.AssistantAuditService;
import com.lifeos.assistant.authorization.AssistantSubject;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Executes only explicitly confirmed, versioned assistant tools with downstream idempotency. */
@Service
public class AssistantToolExecutionService {

    private final AssistantTaskGoalClient taskGoalClient;
    private final AssistantAuditService auditService;
    private final AssistantToolConfirmationService confirmationService;

    public AssistantToolExecutionService(
            AssistantTaskGoalClient taskGoalClient,
            AssistantAuditService auditService,
            AssistantToolConfirmationService confirmationService) {
        this.taskGoalClient = taskGoalClient;
        this.auditService = auditService;
        this.confirmationService = confirmationService;
    }

    public AssistantTaskGoalClient.TaskCreationResult execute(
            AssistantSubject subject,
            UUID conversationId,
            AssistantToolOperation operation,
            String title,
            Integer priority,
            Instant dueAt,
            String idempotencyKey,
            boolean confirmed) {
        if (operation != AssistantToolOperation.DRAFT_TASK
                && operation != AssistantToolOperation.DRAFT_GOAL
                && operation != AssistantToolOperation.DRAFT_FINANCIAL_NOTE) {
            audit(conversationId, subject.accountId(), operation, AssistantAuditOutcome.TOOL_REJECTED, "OPERATION_NOT_ALLOWED");
            throw new AssistantToolOperationNotAllowedException();
        }
        if (!confirmed) {
            audit(conversationId, subject.accountId(), operation, AssistantAuditOutcome.TOOL_REJECTED, "CONFIRMATION_REQUIRED");
            throw new AssistantToolConfirmationRequiredException();
        }
        confirmationService.confirm(
                conversationId, subject.accountId(), operation, title, priority, dueAt, idempotencyKey);
        if (operation == AssistantToolOperation.DRAFT_FINANCIAL_NOTE) {
            audit(conversationId, subject.accountId(), operation, AssistantAuditOutcome.TOOL_EXECUTED, "PROPOSED");
            return new AssistantTaskGoalClient.TaskCreationResult(
                    null,
                    title,
                    "PROPOSED",
                    0,
                    null,
                    null,
                    null,
                    null,
                    priority == null ? 0 : priority,
                    dueAt);
        }
        long started = System.nanoTime();
        try {
            AssistantTaskGoalClient.TaskCreationResult result = operation == AssistantToolOperation.DRAFT_TASK
                    ? taskGoalClient.createTask(subject, title, priority, dueAt, idempotencyKey)
                    : taskGoalClient.createGoal(subject, title, priority, dueAt, idempotencyKey);
            audit(
                    conversationId,
                    subject.accountId(),
                    operation,
                    AssistantAuditOutcome.TOOL_EXECUTED,
                    "COMPLETED",
                    started);
            return result;
        } catch (AssistantTaskToolDeniedException exception) {
            audit(
                    conversationId,
                    subject.accountId(),
                    operation,
                    AssistantAuditOutcome.TOOL_REJECTED,
                    "DENIED",
                    started);
            throw exception;
        } catch (AssistantTaskToolUnavailableException exception) {
            audit(
                    conversationId,
                    subject.accountId(),
                    operation,
                    AssistantAuditOutcome.TOOL_FAILED,
                    "UNAVAILABLE",
                    started);
            throw exception;
        }
    }

    private void audit(
            UUID conversationId,
            UUID accountId,
            AssistantToolOperation operation,
            AssistantAuditOutcome outcome,
            String state) {
        audit(conversationId, accountId, operation, outcome, state, 0L);
    }

    private void audit(
            UUID conversationId,
            UUID accountId,
            AssistantToolOperation operation,
            AssistantAuditOutcome outcome,
            String state,
            long started) {
        auditService.record(new AssistantAuditRecord(
                conversationId,
                accountId,
                AssistantAuditRequestKind.TOOL_EXECUTION,
                outcome,
                "assistant-tool-v1",
                null,
                0,
                0,
                0,
                "NONE",
                "NONE",
                "not-invoked",
                "not-invoked",
                "NOT_GENERATED",
                null,
                0,
                null,
                operation.name(),
                state,
                started == 0L ? 0L : (System.nanoTime() - started) / 1_000_000L,
                correlationId()));
    }

    private static String correlationId() {
        return com.lifeos.assistant.observability.RequestContext.CORRELATION_ID.isBound()
                ? com.lifeos.assistant.observability.RequestContext.CORRELATION_ID.get()
                : "unbound";
    }
}
