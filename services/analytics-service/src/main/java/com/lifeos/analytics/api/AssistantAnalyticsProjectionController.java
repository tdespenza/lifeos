package com.lifeos.analytics.api;

import com.lifeos.analytics.config.AnalyticsProperties;
import com.lifeos.analytics.config.GatewayProofVerifier;
import com.lifeos.analytics.projection.AnalyticsProjectionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Workload-authenticated, aggregate-only Analytics projection for AI recommendations. */
@RestController
public class AssistantAnalyticsProjectionController {

    static final String PATH = "/api/v1/analytics/internal/assistant-insights";
    private static final String WORKLOAD_IDENTITY = "X-LifeOS-Workload-Identity";
    private static final String WORKLOAD_TOKEN = "X-LifeOS-Workload-Token";

    private final AnalyticsProjectionService projections;
    private final GatewayProofVerifier proofVerifier;
    private final AnalyticsProperties properties;

    public AssistantAnalyticsProjectionController(
            AnalyticsProjectionService projections,
            GatewayProofVerifier proofVerifier,
            AnalyticsProperties properties) {
        this.projections = projections;
        this.proofVerifier = proofVerifier;
        this.properties = properties;
    }

    @PostMapping(PATH)
    public ResponseEntity<AnalyticsInsightsProjectionResponse> insights(
            @RequestHeader(value = WORKLOAD_IDENTITY, required = false) String workloadIdentity,
            @RequestHeader(value = WORKLOAD_TOKEN, required = false) String workloadToken,
            @RequestHeader(value = GatewayProofVerifier.PROOF_HEADER, required = false) String gatewayProof,
            @Valid @RequestBody AnalyticsInsightsProjectionRequest request) {
        requireWorkload(workloadIdentity, workloadToken);
        if (!proofVerifier.isValid(
                "POST", PATH, request.subjectId().toString(), request.sessionId().toString(), gatewayProof)) {
            throw new AssistantAnalyticsUnauthorizedException();
        }
        List<AnalyticsProjectionService.ProductivityInsight> insights = projections.productivityInsights(
                request.subjectId(), "personal:" + request.subjectId(), request.periodDays());
        return ResponseEntity.ok(new AnalyticsInsightsProjectionResponse(insights, false, List.of()));
    }

    @ExceptionHandler(AssistantAnalyticsUnauthorizedException.class)
    public ResponseEntity<Void> unauthorized(AssistantAnalyticsUnauthorizedException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    private void requireWorkload(String workloadIdentity, String workloadToken) {
        if (!constantTimeEquals(properties.getAssistantWorkloadIdentity(), workloadIdentity)
                || !constantTimeEquals(properties.getAssistantWorkloadToken(), workloadToken)) {
            throw new AssistantAnalyticsUnauthorizedException();
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    public record AnalyticsInsightsProjectionRequest(
            @NotNull UUID subjectId,
            @NotNull UUID sessionId,
            @NotBlank String authenticationMethod,
            @NotBlank @jakarta.validation.constraints.Size(min = 64, max = 64) String accessTokenProof,
            @Min(1) @Max(90) int periodDays) {
    }

    public record AnalyticsInsightsProjectionResponse(
            List<AnalyticsProjectionService.ProductivityInsight> insights,
            boolean truncated,
            List<String> limitations) {
    }

    public static class AssistantAnalyticsUnauthorizedException extends RuntimeException {
    }
}
