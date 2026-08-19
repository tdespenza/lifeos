package com.lifeos.finance.service;

import com.lifeos.finance.api.FinanceDtos;
import com.lifeos.finance.api.FinanceDtos.BudgetResponse;
import com.lifeos.finance.api.FinanceDtos.CategoryCorrectionResponse;
import com.lifeos.finance.api.FinanceDtos.CategorySpendResponse;
import com.lifeos.finance.api.FinanceDtos.ContributionResponse;
import com.lifeos.finance.api.FinanceDtos.FinancialGoalResponse;
import com.lifeos.finance.api.FinanceDtos.ForecastResponse;
import com.lifeos.finance.api.FinanceDtos.InsightsResponse;
import com.lifeos.finance.api.FinanceDtos.PagedTransactionsResponse;
import com.lifeos.finance.api.FinanceDtos.TransactionResponse;
import com.lifeos.finance.audit.FinanceSecurityAuditEventType;
import com.lifeos.finance.audit.FinanceSecurityAuditService;
import com.lifeos.finance.authorization.FinanceAccessService;
import com.lifeos.finance.authorization.FinanceAuthorizationActions;
import com.lifeos.finance.authorization.FinanceAuthorizationDependencyUnavailable;
import com.lifeos.finance.authorization.FinanceAuthorizationDenied;
import com.lifeos.finance.authorization.FinanceAuthorizationResource;
import com.lifeos.finance.authorization.FinanceSubject;
import com.lifeos.finance.domain.FinanceBudget;
import com.lifeos.finance.domain.FinanceBudgetRepository;
import com.lifeos.finance.domain.FinancialGoal;
import com.lifeos.finance.domain.FinancialGoalContribution;
import com.lifeos.finance.domain.FinancialGoalContributionRepository;
import com.lifeos.finance.domain.FinancialGoalContributionTotal;
import com.lifeos.finance.domain.FinancialGoalRepository;
import com.lifeos.finance.domain.FinancialTransaction;
import com.lifeos.finance.domain.FinancialTransactionRepository;
import com.lifeos.finance.domain.Money;
import com.lifeos.finance.domain.TransactionCategoryCorrection;
import com.lifeos.finance.domain.TransactionCategoryCorrectionRepository;
import com.lifeos.finance.domain.TransactionDirection;
import com.lifeos.finance.idempotency.FinanceIdempotencyExecution;
import com.lifeos.finance.idempotency.FinanceMutationFingerprint;
import com.lifeos.finance.idempotency.FinanceMutationIdempotencyService;
import com.lifeos.finance.idempotency.FinanceMutationOperation;
import com.lifeos.finance.idempotency.FinanceMutationRejectedException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.DayOfWeek;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Finance system-of-record service. Identity is consulted before every action; local owner/tenant
 * predicates are always rechecked to fail closed and avoid cross-account resource enumeration.
 */
@Service
public class FinanceManagementService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_PAGE_INDEX = 1_000;
    private static final int MAX_INSIGHT_POSTINGS = 10_000;
    private static final int MAX_FORECAST_POSTINGS = 10_000;

    private final FinanceAccessService accessService;
    private final FinanceSecurityAuditService auditService;
    private final FinanceMutationFingerprint fingerprint;
    private final FinanceMutationIdempotencyService idempotencyService;
    private final FinanceBudgetRepository budgetRepository;
    private final FinancialTransactionRepository transactionRepository;
    private final TransactionCategoryCorrectionRepository correctionRepository;
    private final FinancialGoalRepository goalRepository;
    private final FinancialGoalContributionRepository contributionRepository;
    private final FinanceForecastService forecastService;
    private final FinanceBudgetOverlapLock budgetOverlapLock;
    private final Clock clock;

    @Autowired
    public FinanceManagementService(
            FinanceAccessService accessService,
            FinanceSecurityAuditService auditService,
            FinanceMutationFingerprint fingerprint,
            FinanceMutationIdempotencyService idempotencyService,
            FinanceBudgetRepository budgetRepository,
            FinancialTransactionRepository transactionRepository,
            TransactionCategoryCorrectionRepository correctionRepository,
            FinancialGoalRepository goalRepository,
            FinancialGoalContributionRepository contributionRepository,
            FinanceForecastService forecastService,
            FinanceBudgetOverlapLock budgetOverlapLock) {
        this(accessService,
                auditService,
                fingerprint,
                idempotencyService,
                budgetRepository,
                transactionRepository,
                correctionRepository,
                goalRepository,
                contributionRepository,
                forecastService,
                budgetOverlapLock,
                Clock.systemUTC());
    }

    FinanceManagementService(
            FinanceAccessService accessService,
            FinanceSecurityAuditService auditService,
            FinanceMutationFingerprint fingerprint,
            FinanceMutationIdempotencyService idempotencyService,
            FinanceBudgetRepository budgetRepository,
            FinancialTransactionRepository transactionRepository,
            TransactionCategoryCorrectionRepository correctionRepository,
            FinancialGoalRepository goalRepository,
            FinancialGoalContributionRepository contributionRepository,
            FinanceForecastService forecastService,
            Clock clock) {
        this(
                accessService,
                auditService,
                fingerprint,
                idempotencyService,
                budgetRepository,
                transactionRepository,
                correctionRepository,
                goalRepository,
                contributionRepository,
                forecastService,
                null,
                clock);
    }

    FinanceManagementService(
            FinanceAccessService accessService,
            FinanceSecurityAuditService auditService,
            FinanceMutationFingerprint fingerprint,
            FinanceMutationIdempotencyService idempotencyService,
            FinanceBudgetRepository budgetRepository,
            FinancialTransactionRepository transactionRepository,
            TransactionCategoryCorrectionRepository correctionRepository,
            FinancialGoalRepository goalRepository,
            FinancialGoalContributionRepository contributionRepository,
            FinanceForecastService forecastService,
            FinanceBudgetOverlapLock budgetOverlapLock,
            Clock clock) {
        this.accessService = accessService;
        this.auditService = auditService;
        this.fingerprint = fingerprint;
        this.idempotencyService = idempotencyService;
        this.budgetRepository = budgetRepository;
        this.transactionRepository = transactionRepository;
        this.correctionRepository = correctionRepository;
        this.goalRepository = goalRepository;
        this.contributionRepository = contributionRepository;
        this.forecastService = forecastService;
        this.budgetOverlapLock = budgetOverlapLock;
        this.clock = clock;
    }

    public FinanceMutationResult<BudgetResponse> createBudget(
            FinanceSubject subject, FinanceDtos.CreateBudgetRequest request, String idempotencyKey) {
        UUID candidateId = UUID.randomUUID();
        authorize(subject,
                FinanceAuthorizationActions.BUDGET_CREATE,
                FinanceAuthorizationResource.forNew(subject, "finance-budget", candidateId));
        String requestFingerprint = fingerprint.fingerprint(
                FinanceMutationOperation.CREATE_BUDGET.name(),
                request.category(),
                request.currency(),
                Long.toString(request.allocationMinor()),
                request.periodStart().toString(),
                request.periodEnd().toString());
        FinanceIdempotencyExecution<BudgetResponse> execution = idempotencyService.execute(
                subject,
                FinanceMutationOperation.CREATE_BUDGET,
                candidateId,
                -1,
                idempotencyKey,
                requestFingerprint,
                BudgetResponse.class,
                id -> createBudgetWithinReservation(subject, id, request));
        return mutationResult(subject, execution, "CREATE_BUDGET");
    }

    public Page<BudgetResponse> listBudgets(FinanceSubject subject, int page, int size) {
        authorize(subject,
                FinanceAuthorizationActions.BUDGET_LIST,
                FinanceAuthorizationResource.forCollection(subject, "finance-budget"));
        return budgetRepository.findByOwnerAccountIdAndTenantIdOrderByPeriodStartDescIdDesc(
                        subject.accountId(), subject.tenantId(), page(page, size))
                .map(this::toBudgetResponse);
    }

    public BudgetResponse getBudget(FinanceSubject subject, UUID id) {
        FinanceBudget budget = findBudgetForRead(subject, id, FinanceAuthorizationActions.BUDGET_READ);
        return toBudgetResponse(budget);
    }

    public FinanceMutationResult<BudgetResponse> updateBudget(
            FinanceSubject subject,
            UUID id,
            long expectedVersion,
            FinanceDtos.UpdateBudgetRequest request,
            String idempotencyKey) {
        FinanceBudget authorizationBudget = findBudgetForRead(subject, id, FinanceAuthorizationActions.BUDGET_UPDATE);
        String requestFingerprint = fingerprint.fingerprint(
                FinanceMutationOperation.UPDATE_BUDGET.name(),
                id.toString(),
                Long.toString(expectedVersion),
                request.currency(),
                Long.toString(request.allocationMinor()),
                request.periodStart().toString(),
                request.periodEnd().toString());
        FinanceIdempotencyExecution<BudgetResponse> execution = idempotencyService.execute(
                subject,
                FinanceMutationOperation.UPDATE_BUDGET,
                authorizationBudget.getId(),
                expectedVersion,
                idempotencyKey,
                requestFingerprint,
                BudgetResponse.class,
                resourceId -> updateBudgetWithinReservation(subject, resourceId, expectedVersion, request));
        return mutationResult(subject, execution, "UPDATE_BUDGET");
    }

    public FinanceMutationResult<TransactionResponse> createTransaction(
            FinanceSubject subject, FinanceDtos.CreateTransactionRequest request, String idempotencyKey) {
        UUID candidateId = UUID.randomUUID();
        authorize(subject,
                FinanceAuthorizationActions.TRANSACTION_CREATE,
                FinanceAuthorizationResource.forNew(subject, "finance-transaction", candidateId));
        String requestFingerprint = fingerprint.fingerprint(
                FinanceMutationOperation.CREATE_TRANSACTION.name(),
                request.currency(),
                Long.toString(request.amountMinor()),
                request.direction().name(),
                request.occurredOn().toString(),
                request.merchant(),
                request.category());
        FinanceIdempotencyExecution<TransactionResponse> execution = idempotencyService.execute(
                subject,
                FinanceMutationOperation.CREATE_TRANSACTION,
                candidateId,
                -1,
                idempotencyKey,
                requestFingerprint,
                TransactionResponse.class,
                id -> createTransactionWithinReservation(subject, id, request));
        return mutationResult(subject, execution, "CREATE_TRANSACTION");
    }

    public PagedTransactionsResponse listTransactions(FinanceSubject subject, int page, int size) {
        authorize(subject,
                FinanceAuthorizationActions.TRANSACTION_LIST,
                FinanceAuthorizationResource.forCollection(subject, "finance-transaction"));
        Page<FinancialTransaction> postings = transactionRepository.findByOwnerAccountIdAndTenantIdOrderByOccurredOnDescIdDesc(
                subject.accountId(), subject.tenantId(), page(page, size));
        return new PagedTransactionsResponse(
                postings.getContent().stream().map(this::toTransactionResponse).toList(),
                postings.getNumber(),
                postings.getSize(),
                postings.hasNext());
    }

    public TransactionResponse getTransaction(FinanceSubject subject, UUID id) {
        FinancialTransaction transaction = findTransactionForRead(subject, id, FinanceAuthorizationActions.TRANSACTION_READ);
        return toTransactionResponse(transaction);
    }

    public FinanceMutationResult<TransactionResponse> categorizeTransaction(
            FinanceSubject subject,
            UUID id,
            long expectedVersion,
            FinanceDtos.CategorizeTransactionRequest request,
            String idempotencyKey) {
        FinancialTransaction authorizationTransaction =
                findTransactionForRead(subject, id, FinanceAuthorizationActions.TRANSACTION_CATEGORIZE);
        String requestFingerprint = fingerprint.fingerprint(
                FinanceMutationOperation.CATEGORIZE_TRANSACTION.name(),
                id.toString(),
                Long.toString(expectedVersion),
                request.category());
        FinanceIdempotencyExecution<TransactionResponse> execution = idempotencyService.execute(
                subject,
                FinanceMutationOperation.CATEGORIZE_TRANSACTION,
                authorizationTransaction.getId(),
                expectedVersion,
                idempotencyKey,
                requestFingerprint,
                TransactionResponse.class,
                resourceId -> categorizeWithinReservation(subject, resourceId, expectedVersion, request));
        return mutationResult(subject, execution, "CATEGORIZE_TRANSACTION");
    }

    public FinanceDtos.PagedCategoryCorrectionsResponse categoryHistory(
            FinanceSubject subject, UUID transactionId, int page, int size) {
        findTransactionForRead(subject, transactionId, FinanceAuthorizationActions.TRANSACTION_READ);
        Page<TransactionCategoryCorrection> corrections =
                correctionRepository.findByTransactionIdOrderByCorrectedAtAscIdAsc(transactionId, page(page, size));
        return new FinanceDtos.PagedCategoryCorrectionsResponse(
                corrections.getContent().stream()
                .map(correction -> new CategoryCorrectionResponse(
                        correction.getPreviousCategory(), correction.getCorrectedCategory(), correction.getCorrectedAt()))
                .toList(),
                corrections.getNumber(),
                corrections.getSize(),
                corrections.hasNext());
    }

    public InsightsResponse insights(FinanceSubject subject, FinanceDtos.InsightQuery query) {
        requireInsightsWindow(query.from(), query.to());
        String currency = Money.requireCurrency(query.currency());
        int categoryPage = query.categoryPage() == null ? 0 : query.categoryPage();
        int categoryPageSize = query.categoryPageSize() == null ? 50 : query.categoryPageSize();
        page(categoryPage, categoryPageSize);
        authorize(subject,
                FinanceAuthorizationActions.INSIGHTS_READ,
                FinanceAuthorizationResource.forCollection(subject, "finance"));
        List<FinancialTransaction> window = transactionRepository
                .findByOwnerAccountIdAndTenantIdAndCurrencyAndOccurredOnBetweenOrderByOccurredOnAscIdAsc(
                        subject.accountId(),
                        subject.tenantId(),
                        currency,
                        query.from(),
                        query.to(),
                        PageRequest.of(0, MAX_INSIGHT_POSTINGS + 1));
        boolean truncated = window.size() > MAX_INSIGHT_POSTINGS;
        List<FinancialTransaction> postings = truncated ? window.subList(0, MAX_INSIGHT_POSTINGS) : window;
        boolean otherCurrencies = transactionRepository.existsByOwnerAccountIdAndTenantIdAndOccurredOnBetweenAndCurrencyNot(
                subject.accountId(), subject.tenantId(), query.from(), query.to(), currency);
        long income = 0;
        long expense = 0;
        Map<String, CategoryTotals> categories = new HashMap<>();
        for (FinancialTransaction posting : postings) {
            CategoryTotals totals = categories.computeIfAbsent(posting.getCurrentCategory(), ignored -> new CategoryTotals());
            if (posting.getDirection() == TransactionDirection.INCOME) {
                income = Money.addExact(income, posting.getAmountMinor());
                totals.addIncome(posting.getAmountMinor());
            } else {
                expense = Money.addExact(expense, posting.getAmountMinor());
                totals.addExpense(posting.getAmountMinor());
            }
        }
        List<CategorySpendResponse> sortedCategoryResponses = categories.entrySet().stream()
                .map(entry -> new CategorySpendResponse(
                        entry.getKey(),
                        entry.getValue().expenseMinor,
                        entry.getValue().incomeMinor,
                        Math.subtractExact(entry.getValue().incomeMinor, entry.getValue().expenseMinor)))
                .sorted(Comparator.comparingLong(CategorySpendResponse::expenseMinor)
                        .reversed()
                        .thenComparing(CategorySpendResponse::category))
                .toList();
        int categoryOffset = Math.toIntExact((long) categoryPage * categoryPageSize);
        int categoryEnd = Math.min(categoryOffset + categoryPageSize, sortedCategoryResponses.size());
        List<CategorySpendResponse> categoryResponses = categoryOffset >= sortedCategoryResponses.size()
                ? List.of()
                : sortedCategoryResponses.subList(categoryOffset, categoryEnd);
        List<String> limitations = new ArrayList<>();
        limitations.add("NO_FX_CONVERSION");
        if (otherCurrencies) {
            limitations.add("OTHER_CURRENCIES_EXCLUDED");
        }
        if (truncated) {
            limitations.add("POSTING_WINDOW_TRUNCATED");
        }
        return new InsightsResponse(
                currency,
                query.from(),
                query.to(),
                income,
                expense,
                Math.subtractExact(income, expense),
                categoryResponses,
                categoryPage,
                categoryPageSize,
                categoryEnd < sortedCategoryResponses.size(),
                truncated,
                limitations);
    }

    public ForecastResponse forecast(FinanceSubject subject, FinanceDtos.ForecastQuery query) {
        String currency = Money.requireCurrency(query.currency());
        int horizonWeeks = query.horizonWeeks() == null ? 4 : query.horizonWeeks();
        LocalDate currentDate = LocalDate.now(clock);
        LocalDate completedWeekEndExclusive = currentDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate earliestWeekStart = completedWeekEndExclusive.minusWeeks(FinanceForecastService.MAXIMUM_COMPLETED_WEEKS);
        authorize(subject,
                FinanceAuthorizationActions.FORECAST_READ,
                FinanceAuthorizationResource.forCollection(subject, "finance"));
        List<FinancialTransaction> postings = transactionRepository
                .findByOwnerAccountIdAndTenantIdAndCurrencyAndOccurredOnBetweenOrderByOccurredOnAscIdAsc(
                        subject.accountId(),
                        subject.tenantId(),
                        currency,
                        earliestWeekStart,
                        completedWeekEndExclusive.minusDays(1),
                        PageRequest.of(0, MAX_FORECAST_POSTINGS + 1));
        boolean truncated = postings.size() > MAX_FORECAST_POSTINGS;
        List<FinancialTransaction> boundedPostings = truncated ? postings.subList(0, MAX_FORECAST_POSTINGS) : postings;
        boolean otherCurrencies = transactionRepository.existsByOwnerAccountIdAndTenantIdAndOccurredOnBetweenAndCurrencyNot(
                subject.accountId(),
                subject.tenantId(),
                earliestWeekStart,
                completedWeekEndExclusive.minusDays(1),
                currency);
        return forecastService.forecast(currency, horizonWeeks, currentDate, boundedPostings, otherCurrencies, truncated);
    }

    public FinanceMutationResult<FinancialGoalResponse> createGoal(
            FinanceSubject subject, FinanceDtos.CreateGoalRequest request, String idempotencyKey) {
        UUID candidateId = UUID.randomUUID();
        authorize(subject,
                FinanceAuthorizationActions.GOAL_CREATE,
                FinanceAuthorizationResource.forNew(subject, "finance-goal", candidateId));
        String requestFingerprint = fingerprint.fingerprint(
                FinanceMutationOperation.CREATE_GOAL.name(),
                request.name(),
                request.currency(),
                Long.toString(request.targetMinor()),
                request.targetDate() == null ? null : request.targetDate().toString());
        FinanceIdempotencyExecution<FinancialGoalResponse> execution = idempotencyService.execute(
                subject,
                FinanceMutationOperation.CREATE_GOAL,
                candidateId,
                -1,
                idempotencyKey,
                requestFingerprint,
                FinancialGoalResponse.class,
                id -> createGoalWithinReservation(subject, id, request));
        return mutationResult(subject, execution, "CREATE_GOAL");
    }

    public Page<FinancialGoalResponse> listGoals(FinanceSubject subject, int page, int size) {
        authorize(subject,
                FinanceAuthorizationActions.GOAL_LIST,
                FinanceAuthorizationResource.forCollection(subject, "finance-goal"));
        Page<FinancialGoal> goals = goalRepository.findByOwnerAccountIdAndTenantIdOrderByCreatedAtDescIdDesc(
                subject.accountId(), subject.tenantId(), page(page, size));
        Map<UUID, Long> totals = totalsByGoal(goals.getContent());
        return goals.map(goal -> toGoalResponse(goal, totals.getOrDefault(goal.getId(), 0L)));
    }

    public FinancialGoalResponse getGoal(FinanceSubject subject, UUID id) {
        FinancialGoal goal = findGoalForRead(subject, id, FinanceAuthorizationActions.GOAL_READ);
        return toGoalResponse(goal, contributionTotal(goal.getId()));
    }

    public FinanceMutationResult<FinancialGoalResponse> updateGoal(
            FinanceSubject subject,
            UUID id,
            long expectedVersion,
            FinanceDtos.UpdateGoalRequest request,
            String idempotencyKey) {
        FinancialGoal authorizationGoal = findGoalForRead(subject, id, FinanceAuthorizationActions.GOAL_UPDATE);
        String requestFingerprint = fingerprint.fingerprint(
                FinanceMutationOperation.UPDATE_GOAL.name(),
                id.toString(),
                Long.toString(expectedVersion),
                request.name(),
                Long.toString(request.targetMinor()),
                request.targetDate() == null ? null : request.targetDate().toString());
        FinanceIdempotencyExecution<FinancialGoalResponse> execution = idempotencyService.execute(
                subject,
                FinanceMutationOperation.UPDATE_GOAL,
                authorizationGoal.getId(),
                expectedVersion,
                idempotencyKey,
                requestFingerprint,
                FinancialGoalResponse.class,
                resourceId -> updateGoalWithinReservation(subject, resourceId, expectedVersion, request));
        return mutationResult(subject, execution, "UPDATE_GOAL");
    }

    public FinanceMutationResult<FinancialGoalResponse> contributeToGoal(
            FinanceSubject subject,
            UUID id,
            long expectedVersion,
            FinanceDtos.CreateContributionRequest request,
            String idempotencyKey) {
        FinancialGoal authorizationGoal = findGoalForRead(subject, id, FinanceAuthorizationActions.GOAL_CONTRIBUTE);
        String requestFingerprint = fingerprint.fingerprint(
                FinanceMutationOperation.CONTRIBUTE_GOAL.name(),
                id.toString(),
                Long.toString(expectedVersion),
                Long.toString(request.amountMinor()),
                request.sourceTransactionId() == null ? null : request.sourceTransactionId().toString());
        FinanceIdempotencyExecution<FinancialGoalResponse> execution = idempotencyService.execute(
                subject,
                FinanceMutationOperation.CONTRIBUTE_GOAL,
                authorizationGoal.getId(),
                expectedVersion,
                idempotencyKey,
                requestFingerprint,
                FinancialGoalResponse.class,
                resourceId -> contributeWithinReservation(subject, resourceId, expectedVersion, request));
        return mutationResult(subject, execution, "CONTRIBUTE_GOAL");
    }

    public FinanceDtos.PagedContributionsResponse listGoalContributions(
            FinanceSubject subject, UUID id, int page, int size) {
        FinancialGoal goal = findGoalForRead(subject, id, FinanceAuthorizationActions.GOAL_READ);
        Page<FinancialGoalContribution> contributions =
                contributionRepository.findByGoalIdOrderByContributedAtAscIdAsc(goal.getId(), page(page, size));
        return new FinanceDtos.PagedContributionsResponse(
                contributions.getContent().stream().map(this::toContributionResponse).toList(),
                contributions.getNumber(),
                contributions.getSize(),
                contributions.hasNext());
    }

    private BudgetResponse createBudgetWithinReservation(
            FinanceSubject subject, UUID id, FinanceDtos.CreateBudgetRequest request) {
        lockBudgetOverlapScope(subject, request.category());
        if (!budgetRepository.findOverlaps(
                        subject.accountId(),
                        subject.tenantId(),
                        request.category().trim(),
                        request.periodStart(),
                        request.periodEnd(),
                        null)
                .isEmpty()) {
            throw new FinanceBudgetOverlapException();
        }
        try {
            FinanceBudget budget = budgetRepository.saveAndFlush(new FinanceBudget(
                    id,
                    subject.accountId(),
                    subject.tenantId(),
                    request.category(),
                    request.currency(),
                    request.allocationMinor(),
                    request.periodStart(),
                    request.periodEnd()));
            return toBudgetResponse(budget);
        } catch (DataIntegrityViolationException exception) {
            throw new FinanceBudgetOverlapException(exception);
        }
    }

    private BudgetResponse updateBudgetWithinReservation(
            FinanceSubject subject, UUID id, long expectedVersion, FinanceDtos.UpdateBudgetRequest request) {
        FinanceBudget budget = budgetRepository.findOwnedForUpdate(id, subject.accountId(), subject.tenantId())
                .orElseThrow(FinanceResourceNotFoundException::new);
        lockBudgetOverlapScope(subject, budget.getCategory());
        if (budget.getVersion() != expectedVersion) {
            throw new FinanceVersionConflictException();
        }
        if (!budgetRepository.findOverlaps(
                        subject.accountId(),
                        subject.tenantId(),
                        budget.getCategory(),
                        request.periodStart(),
                        request.periodEnd(),
                        id)
                .isEmpty()) {
            throw new FinanceBudgetOverlapException();
        }
        try {
            budget.update(request.currency(), request.allocationMinor(), request.periodStart(), request.periodEnd());
            return toBudgetResponse(budgetRepository.saveAndFlush(budget));
        } catch (DataIntegrityViolationException exception) {
            throw new FinanceBudgetOverlapException(exception);
        }
    }

    private void lockBudgetOverlapScope(FinanceSubject subject, String category) {
        if (budgetOverlapLock == null) {
            return;
        }
        budgetOverlapLock.lock(subject.accountId() + "|" + subject.tenantId() + "|" + category.trim());
    }

    private TransactionResponse createTransactionWithinReservation(
            FinanceSubject subject, UUID id, FinanceDtos.CreateTransactionRequest request) {
        FinancialTransaction transaction = transactionRepository.saveAndFlush(new FinancialTransaction(
                id,
                subject.accountId(),
                subject.tenantId(),
                request.currency(),
                request.amountMinor(),
                request.direction(),
                request.occurredOn(),
                request.merchant(),
                request.category()));
        return toTransactionResponse(transaction);
    }

    private TransactionResponse categorizeWithinReservation(
            FinanceSubject subject,
            UUID id,
            long expectedVersion,
            FinanceDtos.CategorizeTransactionRequest request) {
        FinancialTransaction transaction = transactionRepository.findOwnedForUpdate(id, subject.accountId(), subject.tenantId())
                .orElseThrow(FinanceResourceNotFoundException::new);
        if (transaction.getVersion() != expectedVersion) {
            throw new FinanceVersionConflictException();
        }
        try {
            String previous = transaction.correctCategory(request.category());
            correctionRepository.save(new TransactionCategoryCorrection(
                    transaction.getId(), subject.accountId(), previous, transaction.getCurrentCategory(), subject.accountId()));
            return toTransactionResponse(transactionRepository.saveAndFlush(transaction));
        } catch (IllegalArgumentException exception) {
            throw new FinanceMutationRejectedException("Finance category correction conflicts with current state", exception);
        }
    }

    private FinancialGoalResponse createGoalWithinReservation(
            FinanceSubject subject, UUID id, FinanceDtos.CreateGoalRequest request) {
        FinancialGoal goal = goalRepository.saveAndFlush(new FinancialGoal(
                id,
                subject.accountId(),
                subject.tenantId(),
                request.name(),
                request.currency(),
                request.targetMinor(),
                request.targetDate()));
        return toGoalResponse(goal, 0);
    }

    private FinancialGoalResponse updateGoalWithinReservation(
            FinanceSubject subject, UUID id, long expectedVersion, FinanceDtos.UpdateGoalRequest request) {
        FinancialGoal goal = goalRepository.findOwnedForUpdate(id, subject.accountId(), subject.tenantId())
                .orElseThrow(FinanceResourceNotFoundException::new);
        if (goal.getVersion() != expectedVersion) {
            throw new FinanceVersionConflictException();
        }
        goal.update(request.name(), request.targetMinor(), request.targetDate());
        FinancialGoal saved = goalRepository.saveAndFlush(goal);
        return toGoalResponse(saved, contributionTotal(saved.getId()));
    }

    private FinancialGoalResponse contributeWithinReservation(
            FinanceSubject subject,
            UUID id,
            long expectedVersion,
            FinanceDtos.CreateContributionRequest request) {
        FinancialGoal goal = goalRepository.findOwnedForUpdate(id, subject.accountId(), subject.tenantId())
                .orElseThrow(FinanceResourceNotFoundException::new);
        if (goal.getVersion() != expectedVersion) {
            throw new FinanceVersionConflictException();
        }
        verifyContributionSource(subject, goal, request);
        if (request.sourceTransactionId() != null
                && contributionRepository.findByGoalIdAndSourceTransactionId(goal.getId(), request.sourceTransactionId())
                        .isPresent()) {
            throw new FinanceContributionConflictException();
        }
        try {
            contributionRepository.save(new FinancialGoalContribution(
                    UUID.randomUUID(),
                    goal.getId(),
                    subject.accountId(),
                    subject.tenantId(),
                    request.amountMinor(),
                    request.sourceTransactionId()));
            // The aggregate query flushes this transaction, so it already includes the appended
            // immutable row. Do not add the request amount a second time.
            long total = contributionTotal(goal.getId());
            goal.touch();
            return toGoalResponse(goalRepository.saveAndFlush(goal), total);
        } catch (DataIntegrityViolationException exception) {
            throw new FinanceContributionConflictException(exception);
        }
    }

    private void verifyContributionSource(
            FinanceSubject subject, FinancialGoal goal, FinanceDtos.CreateContributionRequest request) {
        if (request.sourceTransactionId() == null) {
            return;
        }
        FinancialTransaction source = transactionRepository
                .findByIdAndOwnerAccountIdAndTenantIdAndCurrency(
                        request.sourceTransactionId(), subject.accountId(), subject.tenantId(), goal.getCurrency())
                .orElseThrow(FinanceResourceNotFoundException::new);
        if (request.amountMinor() > source.getAmountMinor()) {
            throw new FinanceContributionConflictException();
        }
    }

    private FinanceBudget findBudgetForRead(FinanceSubject subject, UUID id, String action) {
        FinanceBudget budget = budgetRepository.findByIdAndOwnerAccountIdAndTenantId(id, subject.accountId(), subject.tenantId())
                .orElse(null);
        if (budget == null) {
            denyAbsentResource(subject, action, "finance-budget", id);
        }
        authorize(subject,
                action,
                FinanceAuthorizationResource.forExisting(subject, "finance-budget", id, budget.getOwnerAccountId()));
        return budget;
    }

    private FinancialTransaction findTransactionForRead(FinanceSubject subject, UUID id, String action) {
        FinancialTransaction transaction = transactionRepository
                .findByIdAndOwnerAccountIdAndTenantId(id, subject.accountId(), subject.tenantId())
                .orElse(null);
        if (transaction == null) {
            denyAbsentResource(subject, action, "finance-transaction", id);
        }
        authorize(subject,
                action,
                FinanceAuthorizationResource.forExisting(subject, "finance-transaction", id, transaction.getOwnerAccountId()));
        return transaction;
    }

    private FinancialGoal findGoalForRead(FinanceSubject subject, UUID id, String action) {
        FinancialGoal goal = goalRepository.findByIdAndOwnerAccountIdAndTenantId(id, subject.accountId(), subject.tenantId())
                .orElse(null);
        if (goal == null) {
            denyAbsentResource(subject, action, "finance-goal", id);
        }
        authorize(subject,
                action,
                FinanceAuthorizationResource.forExisting(subject, "finance-goal", id, goal.getOwnerAccountId()));
        return goal;
    }

    private void denyAbsentResource(FinanceSubject subject, String action, String resourceType, UUID id) {
        try {
            authorize(subject, action, FinanceAuthorizationResource.forMissing(subject, resourceType, id));
        } catch (FinanceAuthorizationDenied exception) {
            // Identity intentionally uses the same owner mismatch for missing and cross-account records.
        }
        throw new FinanceResourceNotFoundException();
    }

    private void authorize(FinanceSubject subject, String action, FinanceAuthorizationResource resource) {
        try {
            accessService.authorize(subject, action, resource);
            auditService.record(FinanceSecurityAuditEventType.AUTHORIZATION_ALLOWED, subject.accountId(), action);
        } catch (FinanceAuthorizationDenied exception) {
            auditService.record(FinanceSecurityAuditEventType.AUTHORIZATION_DENIED, subject.accountId(), action);
            throw exception;
        } catch (FinanceAuthorizationDependencyUnavailable exception) {
            auditService.record(
                    FinanceSecurityAuditEventType.AUTHORIZATION_DEPENDENCY_UNAVAILABLE, subject.accountId(), action);
            throw exception;
        }
    }

    private <T> FinanceMutationResult<T> mutationResult(
            FinanceSubject subject, FinanceIdempotencyExecution<T> execution, String outcome) {
        auditService.record(
                execution.replayed()
                        ? FinanceSecurityAuditEventType.MUTATION_REPLAYED
                        : FinanceSecurityAuditEventType.MUTATION_COMPLETED,
                subject.accountId(),
                outcome);
        return new FinanceMutationResult<>(
                execution.body(), execution.replayed(), execution.responseStatus(), execution.responseLocation());
    }

    private static PageRequest page(int requestedPage, int requestedSize) {
        if (requestedPage < 0
                || requestedPage > MAX_PAGE_INDEX
                || requestedSize < 1
                || requestedSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page and pageSize exceed Finance bounds");
        }
        return PageRequest.of(requestedPage, requestedSize);
    }

    private static void requireInsightsWindow(LocalDate from, LocalDate to) {
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days < 1 || days > 366) {
            throw new FinanceInsightsWindowException();
        }
    }

    private BudgetResponse toBudgetResponse(FinanceBudget budget) {
        return new BudgetResponse(
                budget.getId(),
                budget.getCategory(),
                budget.getCurrency(),
                budget.getAllocationMinor(),
                budget.getPeriodStart(),
                budget.getPeriodEnd(),
                budget.getVersion());
    }

    private TransactionResponse toTransactionResponse(FinancialTransaction transaction) {
        return new TransactionResponse(
                transaction.getId(),
                transaction.getCurrency(),
                transaction.getAmountMinor(),
                transaction.getDirection(),
                transaction.getOccurredOn(),
                transaction.getMerchant(),
                transaction.getInitialCategory(),
                transaction.getCurrentCategory(),
                transaction.getVersion());
    }

    private FinancialGoalResponse toGoalResponse(FinancialGoal goal, long contributedMinor) {
        return new FinancialGoalResponse(
                goal.getId(),
                goal.getName(),
                goal.getCurrency(),
                goal.getTargetMinor(),
                goal.getTargetDate(),
                contributedMinor,
                contributedMinor >= goal.getTargetMinor(),
                goal.getVersion());
    }

    private ContributionResponse toContributionResponse(FinancialGoalContribution contribution) {
        return new ContributionResponse(
                contribution.getId(),
                contribution.getAmountMinor(),
                contribution.getSourceTransactionId(),
                contribution.getContributedAt());
    }

    private long contributionTotal(UUID goalId) {
        long total = 0;
        for (Long amount : contributionRepository.findAmountsByGoalId(goalId)) {
            total = Money.addExact(total, amount);
        }
        return total;
    }

    private Map<UUID, Long> totalsByGoal(List<FinancialGoal> goals) {
        if (goals.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Long> totals = new HashMap<>();
        List<UUID> goalIds = goals.stream().map(FinancialGoal::getId).toList();
        for (FinancialGoalContributionTotal total : contributionRepository.findTotalsByGoalIds(goalIds)) {
            totals.put(total.getGoalId(), total.getTotalMinor());
        }
        return totals;
    }

    private static final class CategoryTotals {

        private long incomeMinor;
        private long expenseMinor;

        private void addIncome(long amount) {
            incomeMinor = Money.addExact(incomeMinor, amount);
        }

        private void addExpense(long amount) {
            expenseMinor = Money.addExact(expenseMinor, amount);
        }
    }
}
