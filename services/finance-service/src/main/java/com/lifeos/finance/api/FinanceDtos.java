package com.lifeos.finance.api;

import com.lifeos.finance.domain.TransactionDirection;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Public Finance request/response shapes. Monetary values are integer minor units, never decimal or floating point. */
public final class FinanceDtos {

    private FinanceDtos() {
    }

    public record CreateBudgetRequest(
            @NotBlank @Size(max = 64) String category,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
            @Positive long allocationMinor,
            @NotNull LocalDate periodStart,
            @NotNull LocalDate periodEnd) {
    }

    /** The category is deliberately immutable; changing the scope is represented by a new budget. */
    public record UpdateBudgetRequest(
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
            @Positive long allocationMinor,
            @NotNull LocalDate periodStart,
            @NotNull LocalDate periodEnd) {
    }

    public record BudgetResponse(
            UUID id,
            String category,
            String currency,
            long allocationMinor,
            LocalDate periodStart,
            LocalDate periodEnd,
            long version) {
    }

    public record PagedBudgetsResponse(List<BudgetResponse> items, int page, int pageSize, boolean hasNext) {
        public PagedBudgetsResponse {
            items = List.copyOf(items);
        }
    }

    public record CreateTransactionRequest(
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
            @Positive long amountMinor,
            @NotNull TransactionDirection direction,
            @NotNull LocalDate occurredOn,
            @Size(max = 120) String merchant,
            @NotBlank @Size(max = 64) String category) {
    }

    public record CategorizeTransactionRequest(@NotBlank @Size(max = 64) String category) {
    }

    public record TransactionResponse(
            UUID id,
            String currency,
            long amountMinor,
            TransactionDirection direction,
            LocalDate occurredOn,
            String merchant,
            String initialCategory,
            String currentCategory,
            long version) {
    }

    public record CategoryCorrectionResponse(
            String previousCategory, String correctedCategory, Instant correctedAt) {
    }

    public record PagedTransactionsResponse(
            List<TransactionResponse> items, int page, int pageSize, boolean hasNext) {
        public PagedTransactionsResponse {
            items = List.copyOf(items);
        }
    }

    public record PagedCategoryCorrectionsResponse(
            List<CategoryCorrectionResponse> items, int page, int pageSize, boolean hasNext) {
        public PagedCategoryCorrectionsResponse {
            items = List.copyOf(items);
        }
    }

    public record CreateGoalRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
            @Positive long targetMinor,
            LocalDate targetDate) {
    }

    /** Currency is immutable: cross-currency progress is not silently converted. */
    public record UpdateGoalRequest(
            @NotBlank @Size(max = 120) String name, @Positive long targetMinor, LocalDate targetDate) {
    }

    public record CreateContributionRequest(@Positive long amountMinor, UUID sourceTransactionId) {
    }

    public record ContributionResponse(UUID id, long amountMinor, UUID sourceTransactionId, Instant contributedAt) {
    }

    public record PagedContributionsResponse(
            List<ContributionResponse> items, int page, int pageSize, boolean hasNext) {
        public PagedContributionsResponse {
            items = List.copyOf(items);
        }
    }

    public record FinancialGoalResponse(
            UUID id,
            String name,
            String currency,
            long targetMinor,
            LocalDate targetDate,
            long contributedMinor,
            boolean reached,
            long version) {
    }

    public record PagedGoalsResponse(List<FinancialGoalResponse> items, int page, int pageSize, boolean hasNext) {
        public PagedGoalsResponse {
            items = List.copyOf(items);
        }
    }

    public record CategorySpendResponse(String category, long expenseMinor, long incomeMinor, long netMinor) {
    }

    /** Insights use at most 366 calendar days and a fixed result window to stay bounded. */
    public record InsightsResponse(
            String currency,
            LocalDate from,
            LocalDate to,
            long incomeMinor,
            long expenseMinor,
            long netMinor,
            List<CategorySpendResponse> categories,
            int categoryPage,
            int categoryPageSize,
            boolean hasNextCategoryPage,
            boolean truncated,
            List<String> limitations) {
        public InsightsResponse {
            categories = List.copyOf(categories);
            limitations = List.copyOf(limitations);
        }
    }

    /**
     * Absent numeric fields when {@code available=false} are intentional: the service never fills
     * an insufficient history with zeros or fabricated point estimates.
     */
    public record ForecastResponse(
            boolean available,
            String reasonCode,
            String currency,
            int horizonWeeks,
            Integer sampleWeeks,
            Long medianIncomePerWeekMinor,
            Long medianExpensePerWeekMinor,
            Long medianNetPerWeekMinor,
            Long lowerQuartileNetPerWeekMinor,
            Long upperQuartileNetPerWeekMinor,
            Long medianHorizonNetMinor,
            Long lowerQuartileHorizonNetMinor,
            Long upperQuartileHorizonNetMinor,
            LocalDate sourceStart,
            LocalDate sourceEnd,
            String methodology,
            List<String> limitations) {
        public ForecastResponse {
            limitations = List.copyOf(limitations);
        }
    }

    public record InsightQuery(
            @NotNull LocalDate from,
            @NotNull LocalDate to,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
            @Min(0) @Max(1000) Integer categoryPage,
            @Min(1) @Max(100) Integer categoryPageSize) {
    }

    public record ForecastQuery(
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
            @Min(1) @Max(52) Integer horizonWeeks) {
    }
}
