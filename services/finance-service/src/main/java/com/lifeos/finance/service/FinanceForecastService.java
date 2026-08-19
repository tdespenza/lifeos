package com.lifeos.finance.service;

import com.lifeos.finance.api.FinanceDtos.ForecastResponse;
import com.lifeos.finance.domain.FinancialTransaction;
import com.lifeos.finance.domain.Money;
import com.lifeos.finance.domain.TransactionDirection;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Pure, non-persisting robust forecast over completed observed weeks. It has O(n log n) time and
 * O(n) memory for at most 52 weekly buckets; it makes no external FX call and emits no decimals.
 */
@Service
public class FinanceForecastService {

    public static final int MINIMUM_OBSERVED_WEEKS = 8;
    public static final int MAXIMUM_COMPLETED_WEEKS = 52;
    private static final String METHODOLOGY = "NEAREST_RANK_WEEKLY_QUARTILES_V1";

    /**
     * Forecasts from transactions already restricted to the caller, selected currency, and last
     * 52 completed calendar weeks. The caller provides the current date so unit tests are exact.
     */
    public ForecastResponse forecast(
            String currency,
            int horizonWeeks,
            LocalDate currentDate,
            List<FinancialTransaction> transactions,
            boolean otherCurrenciesExist,
            boolean sourceTruncated) {
        if (horizonWeeks < 1 || horizonWeeks > MAXIMUM_COMPLETED_WEEKS) {
            throw new IllegalArgumentException("horizonWeeks must be between 1 and 52");
        }
        LocalDate completedWeekEndExclusive = currentDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate earliestWeekStart = completedWeekEndExclusive.minusWeeks(MAXIMUM_COMPLETED_WEEKS);
        Map<LocalDate, WeeklyTotals> weeks = aggregateObservedWeeks(
                transactions, earliestWeekStart, completedWeekEndExclusive);
        List<String> limitations = new ArrayList<>();
        limitations.add("NO_FX_CONVERSION");
        if (otherCurrenciesExist) {
            limitations.add("OTHER_CURRENCIES_EXCLUDED");
        }
        if (sourceTruncated) {
            limitations.add("POSTING_WINDOW_TRUNCATED");
            return new ForecastResponse(
                    false,
                    "SOURCE_WINDOW_TOO_LARGE",
                    currency,
                    horizonWeeks,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    METHODOLOGY,
                    limitations);
        }
        if (weeks.size() < MINIMUM_OBSERVED_WEEKS) {
            return unavailable(currency, horizonWeeks, weeks.size(), limitations);
        }

        List<WeeklyTotals> samples = weeks.values().stream()
                .sorted(Comparator.comparing(WeeklyTotals::weekStart))
                .toList();
        List<Long> incomes = samples.stream().map(WeeklyTotals::incomeMinor).toList();
        List<Long> expenses = samples.stream().map(WeeklyTotals::expenseMinor).toList();
        List<Long> net = samples.stream().map(WeeklyTotals::netMinor).toList();
        try {
            long medianIncome = nearestRank(incomes, 1, 2);
            long medianExpense = nearestRank(expenses, 1, 2);
            long medianNet = nearestRank(net, 1, 2);
            long lowerQuartileNet = nearestRank(net, 1, 4);
            long upperQuartileNet = nearestRank(net, 3, 4);
            return new ForecastResponse(
                    true,
                    "AVAILABLE",
                    currency,
                    horizonWeeks,
                    samples.size(),
                    medianIncome,
                    medianExpense,
                    medianNet,
                    lowerQuartileNet,
                    upperQuartileNet,
                    Math.multiplyExact(medianNet, horizonWeeks),
                    Math.multiplyExact(lowerQuartileNet, horizonWeeks),
                    Math.multiplyExact(upperQuartileNet, horizonWeeks),
                    samples.getFirst().weekStart(),
                    samples.getLast().weekStart().plusDays(6),
                    METHODOLOGY,
                    limitations);
        } catch (ArithmeticException exception) {
            return new ForecastResponse(
                    false,
                    "AMOUNT_RANGE_EXCEEDED",
                    currency,
                    horizonWeeks,
                    samples.size(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    samples.getFirst().weekStart(),
                    samples.getLast().weekStart().plusDays(6),
                    METHODOLOGY,
                    limitations);
        }
    }

    private ForecastResponse unavailable(String currency, int horizonWeeks, int sampleWeeks, List<String> limitations) {
        return new ForecastResponse(
                false,
                "INSUFFICIENT_HISTORY",
                currency,
                horizonWeeks,
                sampleWeeks,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                METHODOLOGY,
                limitations);
    }

    private Map<LocalDate, WeeklyTotals> aggregateObservedWeeks(
            List<FinancialTransaction> transactions, LocalDate earliestWeekStart, LocalDate completedWeekEndExclusive) {
        Map<LocalDate, WeeklyTotals> weeks = new LinkedHashMap<>();
        for (FinancialTransaction transaction : transactions) {
            LocalDate weekStart = transaction.getOccurredOn().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            if (weekStart.isBefore(earliestWeekStart) || !weekStart.isBefore(completedWeekEndExclusive)) {
                continue;
            }
            WeeklyTotals current = weeks.getOrDefault(weekStart, new WeeklyTotals(weekStart, 0, 0));
            weeks.put(weekStart, transaction.getDirection() == TransactionDirection.INCOME
                    ? current.withIncome(Money.addExact(current.incomeMinor(), transaction.getAmountMinor()))
                    : current.withExpense(Money.addExact(current.expenseMinor(), transaction.getAmountMinor())));
        }
        return weeks;
    }

    /** Nearest-rank percentiles retain exact minor-unit observations rather than inventing half-minor units. */
    private long nearestRank(List<Long> values, int numerator, int denominator) {
        List<Long> sorted = values.stream().sorted().toList();
        int rank = Math.floorDiv(Math.addExact(Math.multiplyExact(sorted.size(), numerator), denominator - 1), denominator);
        return sorted.get(Math.max(0, rank - 1));
    }

    private record WeeklyTotals(LocalDate weekStart, long incomeMinor, long expenseMinor) {

        private WeeklyTotals withIncome(long value) {
            return new WeeklyTotals(weekStart, value, expenseMinor);
        }

        private WeeklyTotals withExpense(long value) {
            return new WeeklyTotals(weekStart, incomeMinor, value);
        }

        private long netMinor() {
            return Math.subtractExact(incomeMinor, expenseMinor);
        }
    }
}
