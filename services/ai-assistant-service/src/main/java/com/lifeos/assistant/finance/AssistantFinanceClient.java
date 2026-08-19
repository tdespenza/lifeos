package com.lifeos.assistant.finance;

import com.lifeos.assistant.authorization.AssistantSubject;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Bounded aggregate-only Finance adapter used by the assistant insight endpoint. */
public interface AssistantFinanceClient {

    FinancialInsightSnapshot insights(
            AssistantSubject subject, LocalDate from, LocalDate to, String currency);

    record FinancialInsightSnapshot(
            String currency,
            LocalDate from,
            LocalDate to,
            long incomeMinor,
            long expenseMinor,
            long netMinor,
            List<Category> categories,
            boolean truncated,
            List<String> limitations) {
    }

    record Category(String category, long incomeMinor, long expenseMinor, long netMinor) {
    }
}
