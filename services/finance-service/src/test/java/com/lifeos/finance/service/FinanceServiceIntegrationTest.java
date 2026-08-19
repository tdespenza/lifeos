package com.lifeos.finance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.reset;

import com.lifeos.finance.api.FinanceDtos;
import com.lifeos.finance.authorization.FinanceAccessService;
import com.lifeos.finance.authorization.FinanceSubject;
import com.lifeos.finance.domain.FinanceBudgetRepository;
import com.lifeos.finance.domain.FinancialGoalContributionRepository;
import com.lifeos.finance.domain.FinancialGoalRepository;
import com.lifeos.finance.domain.FinancialTransactionRepository;
import com.lifeos.finance.domain.TransactionCategoryCorrectionRepository;
import com.lifeos.finance.domain.TransactionDirection;
import com.lifeos.finance.idempotency.FinanceMutationIdempotencyRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** H2 service/database integration coverage for FR31–FR36 persistence and retry semantics. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:finance-service-integration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=finance-integration-password",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration-h2",
    "finance.idempotency-secret=integration-idempotency-secret",
    "finance.audit-client-fingerprint-secret=integration-audit-secret",
    "identity.workload-token=integration-workload-token"
})
class FinanceServiceIntegrationTest {

    private static final String ACCESS_TOKEN_PROOF =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Autowired
    private FinanceManagementService service;

    @Autowired
    private FinanceBudgetRepository budgetRepository;

    @Autowired
    private FinancialTransactionRepository transactionRepository;

    @Autowired
    private TransactionCategoryCorrectionRepository correctionRepository;

    @Autowired
    private FinancialGoalContributionRepository contributionRepository;

    @Autowired
    private FinancialGoalRepository goalRepository;

    @Autowired
    private FinanceMutationIdempotencyRepository idempotencyRepository;

    @Autowired
    private MeterRegistry meterRegistry;

    @MockitoBean
    private FinanceAccessService accessService;

    private FinanceSubject subject;

    @BeforeEach
    void setUp() {
        idempotencyRepository.deleteAll();
        correctionRepository.deleteAll();
        contributionRepository.deleteAll();
        transactionRepository.deleteAll();
        goalRepository.deleteAll();
        budgetRepository.deleteAll();
        reset(accessService);
        subject = new FinanceSubject(UUID.randomUUID(), UUID.randomUUID(), "password", ACCESS_TOKEN_PROOF);
    }

    @Test
    void matchingPostingRetryReplaysOriginalSnapshotAfterCategoryCorrection() {
        FinanceMutationResult<FinanceDtos.TransactionResponse> first = service.createTransaction(
                subject,
                new FinanceDtos.CreateTransactionRequest(
                        "USD", 12_345L, TransactionDirection.EXPENSE, java.time.LocalDate.of(2026, 8, 1), "Store", "food"),
                "finance-transaction-retry-key");

        FinanceMutationResult<FinanceDtos.TransactionResponse> correction = service.categorizeTransaction(
                subject,
                first.body().id(),
                first.body().version(),
                new FinanceDtos.CategorizeTransactionRequest("groceries"),
                "finance-transaction-correction-key");
        FinanceMutationResult<FinanceDtos.TransactionResponse> replay = service.createTransaction(
                subject,
                new FinanceDtos.CreateTransactionRequest(
                        "USD", 12_345L, TransactionDirection.EXPENSE, java.time.LocalDate.of(2026, 8, 1), "Store", "food"),
                "finance-transaction-retry-key");

        assertThat(first.replayed()).isFalse();
        assertThat(correction.body().currentCategory()).isEqualTo("groceries");
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(transactionRepository.count()).isEqualTo(1L);
        assertThat(correctionRepository.count()).isEqualTo(1L);
        assertThat(idempotencyRepository.count()).isEqualTo(2L);
    }

    @Test
    void overlapRejectionDeletesItsPendingReservationAndPreservesOriginalBudget() {
        service.createBudget(
                subject,
                new FinanceDtos.CreateBudgetRequest(
                        "groceries", "USD", 50_000L, java.time.LocalDate.of(2026, 8, 1), java.time.LocalDate.of(2026, 8, 31)),
                "finance-budget-first-key");

        assertThatThrownBy(() -> service.createBudget(
                        subject,
                        new FinanceDtos.CreateBudgetRequest(
                                "groceries",
                                "USD",
                                60_000L,
                                java.time.LocalDate.of(2026, 8, 15),
                                java.time.LocalDate.of(2026, 9, 15)),
                        "finance-budget-overlap-key"))
                .isInstanceOf(FinanceBudgetOverlapException.class);

        assertThat(budgetRepository.count()).isEqualTo(1L);
        assertThat(idempotencyRepository.count()).isEqualTo(1L);
    }

    @Test
    void exposesBoundedDeadlockRetrySignalsWithoutCallerDimensions() {
        assertThat(meterRegistry.find("finance.idempotency.deadlock.retries").counter()).isNotNull();
        assertThat(meterRegistry.find("finance.idempotency.deadlock.exhausted").counter()).isNotNull();
    }

    @Test
    void recordsImmutableGoalContributionAndAdvancesGoalVersion() {
        FinanceMutationResult<FinanceDtos.FinancialGoalResponse> created = service.createGoal(
                subject,
                new FinanceDtos.CreateGoalRequest("Emergency fund", "USD", 100_000L, null),
                "finance-goal-create-key");

        FinanceMutationResult<FinanceDtos.FinancialGoalResponse> contributed = service.contributeToGoal(
                subject,
                created.body().id(),
                created.body().version(),
                new FinanceDtos.CreateContributionRequest(25_000L, null),
                "finance-goal-contribution-key");

        assertThat(contributed.body().contributedMinor()).isEqualTo(25_000L);
        assertThat(contributed.body().version()).isEqualTo(1L);
        assertThat(contributionRepository.count()).isEqualTo(1L);
    }

    @Test
    void returnsBoundedDeterministicCategoryPagesWhileKeepingFullIntegerTotals() {
        service.createTransaction(
                subject,
                new FinanceDtos.CreateTransactionRequest(
                        "USD", 1_000L, TransactionDirection.EXPENSE, java.time.LocalDate.of(2026, 8, 1), null, "food"),
                "finance-insight-food-key");
        service.createTransaction(
                subject,
                new FinanceDtos.CreateTransactionRequest(
                        "USD", 2_000L, TransactionDirection.EXPENSE, java.time.LocalDate.of(2026, 8, 2), null, "housing"),
                "finance-insight-housing-key");

        FinanceDtos.InsightsResponse page = service.insights(
                subject,
                new FinanceDtos.InsightQuery(
                        java.time.LocalDate.of(2026, 8, 1), java.time.LocalDate.of(2026, 8, 31), "USD", 0, 1));

        assertThat(page.expenseMinor()).isEqualTo(3_000L);
        assertThat(page.categories()).hasSize(1);
        assertThat(page.categories().getFirst().category()).isEqualTo("housing");
        assertThat(page.hasNextCategoryPage()).isTrue();
        assertThat(page.limitations()).contains("NO_FX_CONVERSION");
    }
}
