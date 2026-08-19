package com.lifeos.finance.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeos.finance.audit.FinanceSecurityAuditEventRepository;
import com.lifeos.finance.authorization.FinanceAccessService;
import com.lifeos.finance.authorization.FinanceSubject;
import com.lifeos.finance.domain.FinanceBudgetRepository;
import com.lifeos.finance.domain.FinancialGoalContributionRepository;
import com.lifeos.finance.domain.FinancialGoalRepository;
import com.lifeos.finance.domain.FinancialTransactionRepository;
import com.lifeos.finance.domain.TransactionCategoryCorrectionRepository;
import com.lifeos.finance.idempotency.FinanceMutationIdempotencyRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Executable public HTTP contract for immutable postings, conditionals, replay, and scope hiding. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:finance-contract;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=finance-contract-password",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration-h2",
    "finance.idempotency-secret=contract-idempotency-secret",
    "finance.audit-client-fingerprint-secret=contract-audit-secret",
    "identity.workload-token=contract-workload-token"
})
@AutoConfigureMockMvc
class FinanceControllerContractTest {

    private static final String BEARER = "Bearer finance-contract-token";
    private static final String ACCESS_TOKEN_PROOF =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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
    private FinanceSecurityAuditEventRepository auditRepository;

    @MockitoBean
    private FinanceAccessService accessService;

    private FinanceSubject subject;

    @BeforeEach
    void setUp() {
        auditRepository.deleteAll();
        idempotencyRepository.deleteAll();
        correctionRepository.deleteAll();
        contributionRepository.deleteAll();
        transactionRepository.deleteAll();
        goalRepository.deleteAll();
        budgetRepository.deleteAll();
        reset(accessService);
        subject = subject();
        when(accessService.authenticate(anyString())).thenReturn(subject);
    }

    @Test
    void postingRetryReplaysTheExactOriginalSnapshotAfterCategoryCorrection() throws Exception {
        MvcResult first = mockMvc.perform(post("/api/v1/finance/transactions")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("If-None-Match", "*")
                        .header("Idempotency-Key", "finance-contract-transaction-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transactionJson("food")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/finance/transactions/")))
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andReturn();
        JsonNode firstBody = objectMapper.readTree(first.getResponse().getContentAsString());
        UUID id = UUID.fromString(firstBody.path("id").asText());

        mockMvc.perform(put("/api/v1/finance/transactions/{id}/category", id)
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("If-Match", "\"0\"")
                        .header("Idempotency-Key", "finance-contract-correction-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"groceries\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.currentCategory").value("groceries"));

        MvcResult replay = mockMvc.perform(post("/api/v1/finance/transactions")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("If-None-Match", "*")
                        .header("Idempotency-Key", "finance-contract-transaction-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transactionJson("food")))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn();

        assertThat(objectMapper.readTree(replay.getResponse().getContentAsString())).isEqualTo(firstBody);
        assertThat(transactionRepository.count()).isEqualTo(1L);
        assertThat(idempotencyRepository.count()).isEqualTo(2L);
    }

    @Test
    void missingAndCrossAccountPostingReadsUseTheSameGenericNotFoundResponse() throws Exception {
        UUID id = UUID.fromString(objectMapper.readTree(mockMvc.perform(post("/api/v1/finance/transactions")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("If-None-Match", "*")
                        .header("Idempotency-Key", "finance-contract-cross-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transactionJson("food")))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString()).path("id").asText());

        when(accessService.authenticate(anyString())).thenReturn(otherSubject());
        MvcResult cross = mockMvc.perform(get("/api/v1/finance/transactions/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isNotFound())
                .andReturn();
        MvcResult missing = mockMvc.perform(get("/api/v1/finance/transactions/{id}", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isNotFound())
                .andReturn();

        assertThat(cross.getResponse().getContentAsString())
                .isEqualTo(missing.getResponse().getContentAsString())
                .doesNotContain(id.toString());
    }

    @Test
    void requiresConditionalAndIdempotencyHeadersForMoneyWrites() throws Exception {
        mockMvc.perform(post("/api/v1/finance/budgets")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("Idempotency-Key", "finance-contract-budget-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(budgetJson()))
                .andExpect(status().is(428));

        mockMvc.perform(post("/api/v1/finance/budgets")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("If-None-Match", "*")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(budgetJson()))
                .andExpect(status().isBadRequest());
    }

    private FinanceSubject subject() {
        return new FinanceSubject(UUID.randomUUID(), UUID.randomUUID(), "password", ACCESS_TOKEN_PROOF);
    }

    private FinanceSubject otherSubject() {
        return new FinanceSubject(UUID.randomUUID(), UUID.randomUUID(), "password", ACCESS_TOKEN_PROOF);
    }

    private static String transactionJson(String category) {
        return "{\"currency\":\"USD\",\"amountMinor\":12345,\"direction\":\"EXPENSE\","
                + "\"occurredOn\":\"2026-08-01\",\"merchant\":\"Store\",\"category\":\""
                + category + "\"}";
    }

    private static String budgetJson() {
        return "{\"category\":\"food\",\"currency\":\"USD\",\"allocationMinor\":50000,"
                + "\"periodStart\":\"2026-08-01\",\"periodEnd\":\"2026-08-31\"}";
    }
}
