package com.lifeos.assistant.finance;

import com.lifeos.assistant.audit.AssistantAuditOutcome;
import com.lifeos.assistant.audit.AssistantAuditRecord;
import com.lifeos.assistant.audit.AssistantAuditRequestKind;
import com.lifeos.assistant.audit.AssistantAuditService;
import com.lifeos.assistant.authorization.AssistantSubject;
import com.lifeos.assistant.observability.RequestContext;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Returns bounded Finance aggregates without exposing raw postings or requiring a model. */
@Service
public class AssistantFinancialInsightService {

    private static final int MAX_RANGE_DAYS = 366;

    private final AssistantFinanceClient financeClient;
    private final AssistantAuditService auditService;

    public AssistantFinancialInsightService(
            AssistantFinanceClient financeClient, AssistantAuditService auditService) {
        this.financeClient = financeClient;
        this.auditService = auditService;
    }

    public FinancialInsight insight(
            AssistantSubject subject, LocalDate from, LocalDate to, String currency) {
        if (from == null || to == null || currency == null || currency.isBlank()
                || from.isAfter(to) || from.plusDays(MAX_RANGE_DAYS).isBefore(to)) {
            throw new AssistantFinanceUnavailableException();
        }
        long started = System.nanoTime();
        try {
            AssistantFinanceClient.FinancialInsightSnapshot snapshot = financeClient.insights(
                    subject, from, to, currency);
            if (snapshot == null) {
                throw new AssistantFinanceUnavailableException();
            }
            audit(
                    subject,
                    AssistantAuditOutcome.ALLOWED,
                    "FINANCE_INSIGHTS",
                    List.of(),
                    started,
                    snapshot.categories().size());
            return new FinancialInsight(
                    snapshot.currency(),
                    snapshot.from(),
                    snapshot.to(),
                    snapshot.incomeMinor(),
                    snapshot.expenseMinor(),
                    snapshot.netMinor(),
                    snapshot.categories(),
                    snapshot.truncated(),
                    snapshot.limitations());
        } catch (AssistantFinanceDeniedException exception) {
            audit(subject, AssistantAuditOutcome.TOOL_REJECTED, "FINANCE_DENIED", List.of(), started, 0);
            throw exception;
        } catch (AssistantFinanceUnavailableException exception) {
            audit(subject, AssistantAuditOutcome.PROVIDER_FAILED, "FINANCE_UNAVAILABLE", List.of(), started, 0);
            throw exception;
        }
    }

    private void audit(
            AssistantSubject subject,
            AssistantAuditOutcome outcome,
            String summary,
            List<UUID> sourceIds,
            long started,
            int outputCharacters) {
        String correlationId = RequestContext.CORRELATION_ID.isBound()
                ? RequestContext.CORRELATION_ID.get()
                : "unbound";
        auditService.record(new AssistantAuditRecord(
                null,
                subject.accountId(),
                AssistantAuditRequestKind.GENERATION_REQUEST,
                outcome,
                "assistant-finance-insights-v1",
                "finance-insights",
                15,
                8,
                0,
                sourceIds.isEmpty() ? "NONE" : sourceIds.stream().map(UUID::toString).limit(12).reduce((a, b) -> a + "," + b).orElse("NONE"),
                "NONE",
                "deterministic-finance-aggregate",
                "bounded-minor-unit-aggregate-v1",
                summary,
                null,
                outputCharacters,
                null,
                "NONE",
                "NOT_REQUESTED",
                Math.max(0L, (System.nanoTime() - started) / 1_000_000L),
                correlationId));
    }

    public record FinancialInsight(
            String currency,
            LocalDate from,
            LocalDate to,
            long incomeMinor,
            long expenseMinor,
            long netMinor,
            List<AssistantFinanceClient.Category> categories,
            boolean truncated,
            List<String> limitations) {
    }
}
