package com.lifeos.finance.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifeos.finance.api.FinanceDtos.ForecastResponse;
import com.lifeos.finance.domain.FinancialTransaction;
import com.lifeos.finance.domain.TransactionDirection;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure deterministic coverage for the bounded no-false-precision forecast algorithm. */
class FinanceForecastServiceTest {

    private final FinanceForecastService service = new FinanceForecastService();

    @Test
    void returnsNearestRankIntegerForecastFromEightCompletedObservedWeeks() {
        LocalDate currentDate = LocalDate.of(2026, 8, 17); // Monday: the week beginning today is excluded.
        List<FinancialTransaction> postings = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            LocalDate week = currentDate.minusWeeks(8 - index).plusDays(1);
            postings.add(posting(1_000L + index * 100L, TransactionDirection.INCOME, week));
            postings.add(posting(400L + index * 50L, TransactionDirection.EXPENSE, week));
        }
        // This posting belongs to the incomplete current week and must not affect the forecast.
        postings.add(posting(9_999L, TransactionDirection.EXPENSE, currentDate.plusDays(1)));

        ForecastResponse result = service.forecast("USD", 4, currentDate, postings, true, false);

        assertThat(result.available()).isTrue();
        assertThat(result.reasonCode()).isEqualTo("AVAILABLE");
        assertThat(result.sampleWeeks()).isEqualTo(8);
        assertThat(result.medianIncomePerWeekMinor()).isEqualTo(1_300L);
        assertThat(result.medianExpensePerWeekMinor()).isEqualTo(550L);
        assertThat(result.medianNetPerWeekMinor()).isEqualTo(750L);
        assertThat(result.medianHorizonNetMinor()).isEqualTo(3_000L);
        assertThat(result.limitations()).contains("NO_FX_CONVERSION", "OTHER_CURRENCIES_EXCLUDED");
        assertThat(result.methodology()).isEqualTo("NEAREST_RANK_WEEKLY_QUARTILES_V1");
    }

    @Test
    void refusesToInventAForecastWithFewerThanEightObservedCompletedWeeks() {
        LocalDate currentDate = LocalDate.of(2026, 8, 17);
        List<FinancialTransaction> postings = new ArrayList<>();
        for (int index = 0; index < 7; index++) {
            postings.add(posting(
                    500L, TransactionDirection.EXPENSE, currentDate.minusWeeks(7 - index).plusDays(2)));
        }

        ForecastResponse result = service.forecast("USD", 4, currentDate, postings, false, false);

        assertThat(result.available()).isFalse();
        assertThat(result.reasonCode()).isEqualTo("INSUFFICIENT_HISTORY");
        assertThat(result.sampleWeeks()).isEqualTo(7);
        assertThat(result.medianNetPerWeekMinor()).isNull();
        assertThat(result.medianHorizonNetMinor()).isNull();
    }

    @Test
    void refusesPartialForecastWhenTheBoundedPostingWindowWouldTruncate() {
        ForecastResponse result = service.forecast(
                "USD", 4, LocalDate.of(2026, 8, 17), List.of(), false, true);

        assertThat(result.available()).isFalse();
        assertThat(result.reasonCode()).isEqualTo("SOURCE_WINDOW_TOO_LARGE");
        assertThat(result.limitations()).contains("POSTING_WINDOW_TRUNCATED", "NO_FX_CONVERSION");
    }

    private static FinancialTransaction posting(long amountMinor, TransactionDirection direction, LocalDate occurredOn) {
        return new FinancialTransaction(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID().toString(),
                "USD",
                amountMinor,
                direction,
                occurredOn,
                null,
                "test");
    }
}
