package com.lifeos.trustledger.api;

import com.lifeos.trustledger.access.TrustAccessService;
import com.lifeos.trustledger.access.TrustAuthorizationResource;
import com.lifeos.trustledger.access.TrustSubject;
import com.lifeos.trustledger.anchor.TrustDigestAnchorResult;
import com.lifeos.trustledger.anchor.TrustDigestAnchorService;
import com.lifeos.trustledger.config.TrustMediaAnchorProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Internal, workload-authenticated Media command for digest-only session-summary anchoring. */
@RestController
public class TrustSessionSummaryAnchorController {

    static final String PATH = "/api/v1/internal/trust/session-summary-anchors";
    private static final String WORKLOAD_IDENTITY = "X-LifeOS-Workload-Identity";
    private static final String WORKLOAD_TOKEN = "X-LifeOS-Workload-Token";

    private final TrustAccessService accessService;
    private final TrustDigestAnchorService anchorService;
    private final TrustMediaAnchorProperties properties;

    public TrustSessionSummaryAnchorController(
            TrustAccessService accessService,
            TrustDigestAnchorService anchorService,
            TrustMediaAnchorProperties properties) {
        this.accessService = accessService;
        this.anchorService = anchorService;
        this.properties = properties;
    }

    @PostMapping(PATH)
    public ResponseEntity<TrustDigestAnchorResult> anchor(
            @RequestHeader(value = WORKLOAD_IDENTITY, required = false) String workloadIdentity,
            @RequestHeader(value = WORKLOAD_TOKEN, required = false) String workloadToken,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody SessionSummaryAnchorRequest request) {
        requireWorkload(workloadIdentity, workloadToken);
        TrustSubject subject = new TrustSubject(
                request.subjectId(), request.sessionId(), request.authenticationMethod(), request.accessTokenProof());
        accessService.authorize(subject, "trust:session-summary-anchor", TrustAuthorizationResource.forSubject(subject));
        TrustDigestAnchorResult result = anchorService.anchor(
                subject,
                "MEDIA_SESSION_SUMMARY",
                request.artifactId(),
                request.artifactVersion(),
                request.digestSha256(),
                idempotencyKey);
        return ResponseEntity.status(result.state().name().equals("CONFIRMED") ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .body(result);
    }

    @ExceptionHandler(WorkloadUnauthorizedException.class)
    public ResponseEntity<Void> workloadUnauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    private void requireWorkload(String identity, String token) {
        if (!properties.configured() || !constantTimeEquals(properties.getWorkloadIdentity(), identity)
                || !constantTimeEquals(properties.getWorkloadToken(), token)) {
            throw new WorkloadUnauthorizedException();
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) return false;
        return java.security.MessageDigest.isEqual(
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                actual.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public record SessionSummaryAnchorRequest(
            @NotNull UUID subjectId,
            @NotNull UUID sessionId,
            @NotBlank @Size(max = 32) String authenticationMethod,
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String accessTokenProof,
            @NotNull UUID artifactId,
            @jakarta.validation.constraints.Min(0) long artifactVersion,
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String digestSha256) {
    }

    static class WorkloadUnauthorizedException extends RuntimeException {
    }
}
