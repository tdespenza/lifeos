package com.lifeos.analytics.api;

import com.lifeos.analytics.config.AnalyticsProperties;
import com.lifeos.analytics.config.GatewayProofVerifier;
import com.lifeos.analytics.projection.AnalyticsMetricSnapshot;
import com.lifeos.analytics.projection.AnalyticsMetricHistory;
import com.lifeos.analytics.projection.AnalyticsProjectionService;
import com.lifeos.analytics.projection.AnalyticsProjectionService.ProductivityInsight;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated dashboard and internal projection endpoints. */
@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {

    private final AnalyticsProjectionService projections;
    private final GatewayProofVerifier proofVerifier;
    private final AnalyticsProperties properties;

    public AnalyticsController(
            AnalyticsProjectionService projections,
            GatewayProofVerifier proofVerifier,
            AnalyticsProperties properties) {
        this.projections = projections;
        this.proofVerifier = proofVerifier;
        this.properties = properties;
    }

    @GetMapping("/dashboard")
    public ResponseEntity<DashboardResponse> dashboard(
            @RequestParam(defaultValue = "30") @Min(1) @Max(90) int periodDays,
            HttpServletRequest request) {
        UUID account = authenticatedAccount(request);
        String tenant = personalTenant(account);
        requireGatewayProof(request, account);
        List<MetricResponse> metrics = projections.dashboard(account, tenant, periodDays).stream()
                .map(MetricResponse::from)
                .toList();
        return ResponseEntity.ok(new DashboardResponse(account, tenant, periodDays, metrics, "analytics-v1"));
    }

    @GetMapping("/insights")
    public ResponseEntity<InsightsResponse> insights(
            @RequestParam(defaultValue = "30") @Min(1) @Max(90) int periodDays,
            HttpServletRequest request) {
        UUID account = authenticatedAccount(request);
        String tenant = personalTenant(account);
        requireGatewayProof(request, account);
        List<ProductivityInsight> insights = projections.productivityInsights(account, tenant, periodDays);
        return ResponseEntity.ok(new InsightsResponse(account, tenant, periodDays, insights, "analytics-v1"));
    }

    @GetMapping("/trends")
    public ResponseEntity<TrendResponse> trends(
            @RequestParam @NotBlank String metricKey,
            @RequestParam(defaultValue = "30") @Min(1) @Max(90) int periodDays,
            @RequestParam(defaultValue = "30") @Min(1) @Max(90) int days,
            HttpServletRequest request) {
        UUID account = authenticatedAccount(request);
        String tenant = personalTenant(account);
        requireGatewayProof(request, account);
        List<TrendPoint> points = projections.trend(account, tenant, metricKey, periodDays, days).stream()
                .map(TrendPoint::from)
                .toList();
        return ResponseEntity.ok(new TrendResponse(account, tenant, metricKey, periodDays, days, points, "analytics-v1"));
    }

    @PostMapping("/internal/metrics")
    public ResponseEntity<Void> record(@Valid @RequestBody MetricRequest body, HttpServletRequest request) {
        if (!properties.getWorkloadToken().equals(request.getHeader("X-LifeOS-Analytics-Workload-Token"))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        projections.record(body.ownerAccountId(), body.tenantId(), body.metricKey(), body.value(), body.periodDays());
        return ResponseEntity.accepted().build();
    }

    private void requireGatewayProof(HttpServletRequest request, UUID account) {
        String accountHeader = request.getHeader(GatewayProofVerifier.ACCOUNT_HEADER);
        if (!account.toString().equals(accountHeader)
                || !proofVerifier.isValid(
                        request.getMethod(),
                        request.getRequestURI(),
                        accountHeader,
                        request.getHeader(GatewayProofVerifier.SESSION_HEADER),
                        request.getHeader(GatewayProofVerifier.PROOF_HEADER))) {
            throw new AnalyticsUnauthorizedException();
        }
    }

    private static UUID authenticatedAccount(HttpServletRequest request) {
        try {
            return UUID.fromString(request.getHeader(GatewayProofVerifier.ACCOUNT_HEADER));
        } catch (RuntimeException exception) {
            throw new AnalyticsUnauthorizedException();
        }
    }

    private static String personalTenant(UUID account) {
        return "personal:" + account;
    }

    public record DashboardResponse(
            UUID accountId, String tenantId, int periodDays, List<MetricResponse> metrics, String sourceVersion) {}

    public record InsightsResponse(
            UUID accountId,
            String tenantId,
            int periodDays,
            List<ProductivityInsight> insights,
            String sourceVersion) {}

    public record TrendResponse(
            UUID accountId,
            String tenantId,
            String metricKey,
            int periodDays,
            int requestedDays,
            List<TrendPoint> points,
            String sourceVersion) {}

    public record TrendPoint(java.time.LocalDate date, long value, String sourceVersion) {
        static TrendPoint from(AnalyticsMetricHistory observation) {
            return new TrendPoint(
                    observation.getObservationDate(), observation.getMetricValue(), observation.getSourceVersion());
        }
    }

    public record MetricResponse(String key, long value, int periodDays, String sourceVersion) {
        static MetricResponse from(AnalyticsMetricSnapshot snapshot) {
            return new MetricResponse(
                    snapshot.getMetricKey(), snapshot.getMetricValue(), snapshot.getPeriodDays(), snapshot.getSourceVersion());
        }
    }

    public record MetricRequest(
            @NotNull UUID ownerAccountId,
            @NotBlank String tenantId,
            @NotBlank String metricKey,
            long value,
            @Min(1) @Max(90) int periodDays) {}
}
