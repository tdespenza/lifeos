package com.lifeos.finance.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifeos.finance.api.FinanceDtos;
import com.lifeos.finance.authorization.FinanceAccessService;
import com.lifeos.finance.authorization.FinanceSubject;
import com.lifeos.finance.domain.FinanceBudgetRepository;
import com.lifeos.finance.domain.FinancialTransactionRepository;
import com.lifeos.finance.domain.TransactionDirection;
import com.lifeos.finance.service.FinanceBudgetOverlapException;
import com.lifeos.finance.service.FinanceManagementService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL-only coverage for concurrent idempotency convergence and exclusion-constraint overlap safety. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "finance.idempotency-secret=postgres-idempotency-secret",
    "finance.audit-client-fingerprint-secret=postgres-audit-secret",
    "identity.workload-token=postgres-workload-token"
})
class FinancePostgresIntegrationTest {

    private static final String ACCESS_TOKEN_PROOF =
            "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("lifeos_finance")
            .withUsername("lifeos")
            .withPassword("test-only-postgres-password");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
    }

    @Autowired
    private FinanceManagementService service;

    @Autowired
    private FinanceBudgetRepository budgetRepository;

    @Autowired
    private FinancialTransactionRepository transactionRepository;

    @Autowired
    private FinanceMutationIdempotencyRepository idempotencyRepository;

    @MockitoBean
    private FinanceAccessService accessService;

    private FinanceSubject subject;

    @BeforeEach
    void setUp() {
        idempotencyRepository.deleteAll();
        transactionRepository.deleteAll();
        budgetRepository.deleteAll();
        subject = new FinanceSubject(UUID.randomUUID(), UUID.randomUUID(), "password", ACCESS_TOKEN_PROOF);
    }

    @Test
    void concurrentMatchingPostingCreatesConvergeOnOnePostgresRowAndExactReservation() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<UUID> first = executor.submit(() -> createPostingAfterStart(ready, start));
            Future<UUID> second = executor.submit(() -> createPostingAfterStart(ready, start));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            UUID firstId = first.get(15, TimeUnit.SECONDS);
            UUID secondId = second.get(15, TimeUnit.SECONDS);

            assertThat(secondId).isEqualTo(firstId);
            assertThat(transactionRepository.count()).isEqualTo(1L);
            assertThat(idempotencyRepository.count()).isEqualTo(1L);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void concurrentDistinctBudgetKeysProduceOneCommitAndOneDeterministicOverlapRejection() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<CreateAttempt> first = executor.submit(() -> createBudgetAfterStart(ready, start, "budget-overlap-key-a"));
            Future<CreateAttempt> second = executor.submit(() -> createBudgetAfterStart(ready, start, "budget-overlap-key-b"));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<CreateAttempt> attempts = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));

            assertThat(attempts.stream().filter(CreateAttempt::succeeded).count()).isEqualTo(1L);
            assertThat(attempts.stream().map(CreateAttempt::failure).filter(java.util.Objects::nonNull))
                    .allMatch(FinanceBudgetOverlapException.class::isInstance);
            assertThat(budgetRepository.count()).isEqualTo(1L);
            assertThat(idempotencyRepository.findAll()).hasSize(1).allMatch(FinanceMutationIdempotency::isCompleted);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private UUID createPostingAfterStart(CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("concurrent Finance posting create did not receive its start signal");
        }
        return service.createTransaction(
                        subject,
                        new FinanceDtos.CreateTransactionRequest(
                                "USD", 1_000L, TransactionDirection.INCOME, LocalDate.of(2026, 8, 1), null, "income"),
                        "finance-postgres-concurrent-key")
                .body()
                .id();
    }

    private CreateAttempt createBudgetAfterStart(CountDownLatch ready, CountDownLatch start, String key) throws Exception {
        try {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("concurrent Finance budget create did not receive its start signal");
            }
            UUID id = service.createBudget(
                            subject,
                            new FinanceDtos.CreateBudgetRequest(
                                    "housing",
                                    "USD",
                                    100_000L,
                                    LocalDate.of(2026, 8, 1),
                                    LocalDate.of(2026, 8, 31)),
                            key)
                    .body()
                    .id();
            return CreateAttempt.success(id);
        } catch (RuntimeException exception) {
            return CreateAttempt.failure(exception);
        }
    }

    private record CreateAttempt(UUID id, Throwable failure) {

        private static CreateAttempt success(UUID id) {
            return new CreateAttempt(id, null);
        }

        private static CreateAttempt failure(Throwable failure) {
            return new CreateAttempt(null, failure);
        }

        private boolean succeeded() {
            return id != null;
        }
    }
}
