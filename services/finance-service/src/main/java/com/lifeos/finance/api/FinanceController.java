package com.lifeos.finance.api;

import com.lifeos.finance.api.FinanceDtos.BudgetResponse;
import com.lifeos.finance.api.FinanceDtos.FinancialGoalResponse;
import com.lifeos.finance.audit.FinanceSecurityAuditEventType;
import com.lifeos.finance.audit.FinanceSecurityAuditService;
import com.lifeos.finance.authorization.FinanceAccessService;
import com.lifeos.finance.authorization.FinanceAuthenticationFailure;
import com.lifeos.finance.authorization.FinanceAuthorizationDependencyUnavailable;
import com.lifeos.finance.authorization.FinanceSubject;
import com.lifeos.finance.idempotency.FinanceCreatePrecondition;
import com.lifeos.finance.idempotency.FinanceIdempotencyKey;
import com.lifeos.finance.idempotency.FinanceVersionPrecondition;
import com.lifeos.finance.service.FinanceManagementService;
import com.lifeos.finance.service.FinanceMutationResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public self-only Personal Finance HTTP contract. Monetary fields are integer minor units. */
@RestController
@Validated
public class FinanceController {

    private static final String REPLAY_HEADER = "Idempotent-Replayed";

    private final FinanceManagementService service;
    private final FinanceAccessService accessService;
    private final FinanceSecurityAuditService auditService;

    public FinanceController(
            FinanceManagementService service,
            FinanceAccessService accessService,
            FinanceSecurityAuditService auditService) {
        this.service = service;
        this.accessService = accessService;
        this.auditService = auditService;
    }

    @PostMapping("/api/v1/finance/budgets")
    public ResponseEntity<BudgetResponse> createBudget(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestHeader(value = FinanceIdempotencyKey.HEADER_NAME, required = false) List<String> idempotencyKeys,
            @RequestHeader(value = FinanceCreatePrecondition.HEADER_NAME, required = false) List<String> ifNoneMatch,
            @Valid @RequestBody FinanceDtos.CreateBudgetRequest request) {
        FinanceSubject subject = authenticate(authorizationHeader);
        FinanceCreatePrecondition.requireCreateOnly(ifNoneMatch);
        return mutationResponse(service.createBudget(
                subject, request, FinanceIdempotencyKey.requireSingleHeader(idempotencyKeys)));
    }

    @GetMapping("/api/v1/finance/budgets")
    public FinanceDtos.PagedBudgetsResponse listBudgets(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1000) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int pageSize) {
        Page<BudgetResponse> result = service.listBudgets(authenticate(authorizationHeader), page, pageSize);
        return new FinanceDtos.PagedBudgetsResponse(
                result.getContent(), result.getNumber(), result.getSize(), result.hasNext());
    }

    @GetMapping("/api/v1/finance/budgets/{id}")
    public ResponseEntity<BudgetResponse> getBudget(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader) {
        BudgetResponse body = service.getBudget(authenticate(authorizationHeader), id);
        return ResponseEntity.ok().eTag(etag(body.version())).body(body);
    }

    @PutMapping("/api/v1/finance/budgets/{id}")
    public ResponseEntity<BudgetResponse> updateBudget(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestHeader(value = FinanceIdempotencyKey.HEADER_NAME, required = false) List<String> idempotencyKeys,
            @RequestHeader(value = FinanceVersionPrecondition.HEADER_NAME, required = false) List<String> ifMatch,
            @Valid @RequestBody FinanceDtos.UpdateBudgetRequest request) {
        FinanceSubject subject = authenticate(authorizationHeader);
        return mutationResponse(service.updateBudget(
                subject,
                id,
                FinanceVersionPrecondition.requireSingleHeader(ifMatch),
                request,
                FinanceIdempotencyKey.requireSingleHeader(idempotencyKeys)));
    }

    @PostMapping("/api/v1/finance/transactions")
    public ResponseEntity<FinanceDtos.TransactionResponse> createTransaction(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestHeader(value = FinanceIdempotencyKey.HEADER_NAME, required = false) List<String> idempotencyKeys,
            @RequestHeader(value = FinanceCreatePrecondition.HEADER_NAME, required = false) List<String> ifNoneMatch,
            @Valid @RequestBody FinanceDtos.CreateTransactionRequest request) {
        FinanceSubject subject = authenticate(authorizationHeader);
        FinanceCreatePrecondition.requireCreateOnly(ifNoneMatch);
        return mutationResponse(service.createTransaction(
                subject, request, FinanceIdempotencyKey.requireSingleHeader(idempotencyKeys)));
    }

    @GetMapping("/api/v1/finance/transactions")
    public FinanceDtos.PagedTransactionsResponse listTransactions(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1000) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int pageSize) {
        return service.listTransactions(authenticate(authorizationHeader), page, pageSize);
    }

    @GetMapping("/api/v1/finance/transactions/{id}")
    public ResponseEntity<FinanceDtos.TransactionResponse> getTransaction(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader) {
        FinanceDtos.TransactionResponse body = service.getTransaction(authenticate(authorizationHeader), id);
        return ResponseEntity.ok().eTag(etag(body.version())).body(body);
    }

    @PutMapping("/api/v1/finance/transactions/{id}/category")
    public ResponseEntity<FinanceDtos.TransactionResponse> categorizeTransaction(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestHeader(value = FinanceIdempotencyKey.HEADER_NAME, required = false) List<String> idempotencyKeys,
            @RequestHeader(value = FinanceVersionPrecondition.HEADER_NAME, required = false) List<String> ifMatch,
            @Valid @RequestBody FinanceDtos.CategorizeTransactionRequest request) {
        FinanceSubject subject = authenticate(authorizationHeader);
        return mutationResponse(service.categorizeTransaction(
                subject,
                id,
                FinanceVersionPrecondition.requireSingleHeader(ifMatch),
                request,
                FinanceIdempotencyKey.requireSingleHeader(idempotencyKeys)));
    }

    @GetMapping("/api/v1/finance/transactions/{id}/category-history")
    public FinanceDtos.PagedCategoryCorrectionsResponse categoryHistory(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1000) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int pageSize) {
        return service.categoryHistory(authenticate(authorizationHeader), id, page, pageSize);
    }

    @GetMapping("/api/v1/finance/insights")
    public FinanceDtos.InsightsResponse insights(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam String currency,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1000) Integer categoryPage,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) Integer categoryPageSize) {
        return service.insights(
                authenticate(authorizationHeader),
                new FinanceDtos.InsightQuery(from, to, currency, categoryPage, categoryPageSize));
    }

    @GetMapping("/api/v1/finance/forecast")
    public FinanceDtos.ForecastResponse forecast(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestParam String currency,
            @RequestParam(defaultValue = "4") @Min(1) @Max(52) Integer horizonWeeks) {
        return service.forecast(
                authenticate(authorizationHeader), new FinanceDtos.ForecastQuery(currency, horizonWeeks));
    }

    @PostMapping("/api/v1/finance/goals")
    public ResponseEntity<FinancialGoalResponse> createGoal(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestHeader(value = FinanceIdempotencyKey.HEADER_NAME, required = false) List<String> idempotencyKeys,
            @RequestHeader(value = FinanceCreatePrecondition.HEADER_NAME, required = false) List<String> ifNoneMatch,
            @Valid @RequestBody FinanceDtos.CreateGoalRequest request) {
        FinanceSubject subject = authenticate(authorizationHeader);
        FinanceCreatePrecondition.requireCreateOnly(ifNoneMatch);
        return mutationResponse(service.createGoal(subject, request, FinanceIdempotencyKey.requireSingleHeader(idempotencyKeys)));
    }

    @GetMapping("/api/v1/finance/goals")
    public FinanceDtos.PagedGoalsResponse listGoals(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1000) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int pageSize) {
        Page<FinancialGoalResponse> result = service.listGoals(authenticate(authorizationHeader), page, pageSize);
        return new FinanceDtos.PagedGoalsResponse(
                result.getContent(), result.getNumber(), result.getSize(), result.hasNext());
    }

    @GetMapping("/api/v1/finance/goals/{id}")
    public ResponseEntity<FinancialGoalResponse> getGoal(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader) {
        FinancialGoalResponse body = service.getGoal(authenticate(authorizationHeader), id);
        return ResponseEntity.ok().eTag(etag(body.version())).body(body);
    }

    @PutMapping("/api/v1/finance/goals/{id}")
    public ResponseEntity<FinancialGoalResponse> updateGoal(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestHeader(value = FinanceIdempotencyKey.HEADER_NAME, required = false) List<String> idempotencyKeys,
            @RequestHeader(value = FinanceVersionPrecondition.HEADER_NAME, required = false) List<String> ifMatch,
            @Valid @RequestBody FinanceDtos.UpdateGoalRequest request) {
        FinanceSubject subject = authenticate(authorizationHeader);
        return mutationResponse(service.updateGoal(
                subject,
                id,
                FinanceVersionPrecondition.requireSingleHeader(ifMatch),
                request,
                FinanceIdempotencyKey.requireSingleHeader(idempotencyKeys)));
    }

    @PostMapping("/api/v1/finance/goals/{id}/contributions")
    public ResponseEntity<FinancialGoalResponse> contributeToGoal(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestHeader(value = FinanceIdempotencyKey.HEADER_NAME, required = false) List<String> idempotencyKeys,
            @RequestHeader(value = FinanceVersionPrecondition.HEADER_NAME, required = false) List<String> ifMatch,
            @Valid @RequestBody FinanceDtos.CreateContributionRequest request) {
        FinanceSubject subject = authenticate(authorizationHeader);
        return mutationResponse(service.contributeToGoal(
                subject,
                id,
                FinanceVersionPrecondition.requireSingleHeader(ifMatch),
                request,
                FinanceIdempotencyKey.requireSingleHeader(idempotencyKeys)));
    }

    @GetMapping("/api/v1/finance/goals/{id}/contributions")
    public FinanceDtos.PagedContributionsResponse listGoalContributions(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1000) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int pageSize) {
        return service.listGoalContributions(authenticate(authorizationHeader), id, page, pageSize);
    }

    private FinanceSubject authenticate(String authorizationHeader) {
        try {
            return accessService.authenticate(authorizationHeader);
        } catch (FinanceAuthenticationFailure exception) {
            auditService.record(FinanceSecurityAuditEventType.AUTHENTICATION_FAILED, null, "AUTHENTICATION_FAILED");
            throw exception;
        } catch (FinanceAuthorizationDependencyUnavailable exception) {
            auditService.record(
                    FinanceSecurityAuditEventType.AUTHENTICATION_DEPENDENCY_UNAVAILABLE,
                    null,
                    "AUTHENTICATION_DEPENDENCY_UNAVAILABLE");
            throw exception;
        }
    }

    private static <T> ResponseEntity<T> mutationResponse(FinanceMutationResult<T> result) {
        long version = extractVersion(result.body());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(result.responseStatus())
                .eTag(etag(version))
                .header(REPLAY_HEADER, Boolean.toString(result.replayed()));
        if (result.responseLocation() != null) {
            response.header(HttpHeaders.LOCATION, result.responseLocation());
        }
        return response.body(result.body());
    }

    private static long extractVersion(Object body) {
        return switch (body) {
            case BudgetResponse response -> response.version();
            case FinanceDtos.TransactionResponse response -> response.version();
            case FinancialGoalResponse response -> response.version();
            default -> throw new IllegalStateException("unsupported Finance mutation response type");
        };
    }

    private static String etag(long version) {
        return "\"" + version + "\"";
    }
}
