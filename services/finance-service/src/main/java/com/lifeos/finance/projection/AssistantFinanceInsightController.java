package com.lifeos.finance.projection;

import com.lifeos.finance.api.FinanceDtos;
import com.lifeos.finance.authorization.FinanceSubject;
import com.lifeos.finance.config.FinanceAssistantProjectionProperties;
import com.lifeos.finance.service.FinanceManagementService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Workload-authenticated aggregate-only Finance projection for the assistant. */
@RestController
public class AssistantFinanceInsightController {

    static final String PATH = "/api/v1/internal/assistant/finance-insights";
    static final String WORKLOAD_IDENTITY = "X-LifeOS-Workload-Identity";
    static final String WORKLOAD_TOKEN = "X-LifeOS-Workload-Token";

    private final FinanceManagementService financeService;
    private final FinanceAssistantProjectionProperties properties;

    public AssistantFinanceInsightController(
            FinanceManagementService financeService, FinanceAssistantProjectionProperties properties) {
        this.financeService = financeService;
        this.properties = properties;
    }

    @PostMapping(PATH)
    public ResponseEntity<FinanceInsightsProjectionResponse> insights(
            @RequestHeader(value = WORKLOAD_IDENTITY, required = false) String workloadIdentity,
            @RequestHeader(value = WORKLOAD_TOKEN, required = false) String workloadToken,
            @Valid @RequestBody FinanceInsightsProjectionRequest request) {
        requireWorkload(workloadIdentity, workloadToken);
        FinanceSubject subject = new FinanceSubject(
                request.subjectId(), request.sessionId(), request.authenticationMethod(), request.accessTokenProof());
        FinanceDtos.InsightsResponse response = financeService.insights(
                subject,
                new FinanceDtos.InsightQuery(request.from(), request.to(), request.currency(), 0, 32));
        return ResponseEntity.ok(FinanceInsightsProjectionResponse.from(response));
    }

    @ExceptionHandler(AssistantFinanceWorkloadUnauthorizedException.class)
    public ResponseEntity<Void> workloadUnauthorized(AssistantFinanceWorkloadUnauthorizedException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    private void requireWorkload(String workloadIdentity, String workloadToken) {
        if (!properties.configured()
                || !constantTimeEquals(properties.getWorkloadIdentity(), workloadIdentity)
                || !constantTimeEquals(properties.getWorkloadToken(), workloadToken)) {
            throw new AssistantFinanceWorkloadUnauthorizedException();
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                actual.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public record FinanceInsightsProjectionRequest(
            @NotNull UUID subjectId,
            @NotNull UUID sessionId,
            @NotBlank String authenticationMethod,
            @NotBlank @jakarta.validation.constraints.Size(min = 64, max = 64) String accessTokenProof,
            @NotNull LocalDate from,
            @NotNull LocalDate to,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency) {
    }

    public record FinanceInsightsProjectionResponse(
            String currency,
            LocalDate from,
            LocalDate to,
            long incomeMinor,
            long expenseMinor,
            long netMinor,
            List<Category> categories,
            boolean truncated,
            List<String> limitations) {

        static FinanceInsightsProjectionResponse from(FinanceDtos.InsightsResponse response) {
            return new FinanceInsightsProjectionResponse(
                    response.currency(),
                    response.from(),
                    response.to(),
                    response.incomeMinor(),
                    response.expenseMinor(),
                    response.netMinor(),
                    response.categories().stream()
                            .map(category -> new Category(
                                    category.category(),
                                    category.incomeMinor(),
                                    category.expenseMinor(),
                                    category.netMinor()))
                            .toList(),
                    response.truncated(),
                    response.limitations());
        }
    }

    public record Category(String category, long incomeMinor, long expenseMinor, long netMinor) {
    }

    /** Workload authentication intentionally does not disclose Finance resources on failure. */
    public static class AssistantFinanceWorkloadUnauthorizedException extends RuntimeException {
    }
}
