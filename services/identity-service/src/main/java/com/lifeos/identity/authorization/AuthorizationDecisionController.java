package com.lifeos.identity.authorization;

import com.lifeos.identity.auth.AuthorizationDependencyUnavailableException;
import com.lifeos.identity.auth.ClientAddressResolver;
import com.lifeos.identity.auth.InternalAuthorizationRequestFilter;
import com.lifeos.identity.auth.InternalWorkloadIdentityVerifier;
import com.lifeos.identity.auth.InternalWorkloadRateLimiter;
import com.lifeos.identity.auth.SecurityAuditEventType;
import com.lifeos.identity.auth.SecurityAuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authenticated internal REST adapter for the transport-independent authorization authority.
 *
 * <p>This endpoint is not a public client API. It is intentionally small while the repository has
 * no generated gRPC contract module: workload authentication, strict DTOs, bounded reason codes,
 * and durable audit recording preserve a safe migration path to ADR-007's target transport.
 */
@RestController
public class AuthorizationDecisionController {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationDecisionController.class);

    private final AuthorizationDecisionService decisionService;
    private final InternalWorkloadIdentityVerifier workloadIdentityVerifier;
    private final InternalWorkloadRateLimiter workloadRateLimiter;
    private final SecurityAuditService auditService;
    private final ClientAddressResolver clientAddressResolver;
    private final AuthorizationMetrics metrics;

    /**
     * Creates the internal decision adapter.
     *
     * @param decisionService transport-independent policy authority
     * @param workloadIdentityVerifier internal caller verifier
     * @param workloadRateLimiter distributed workload request limiter
     * @param auditService redacted independent audit persistence
     * @param clientAddressResolver trusted-address resolver for fingerprinting only
     * @param metrics bounded decision metrics
     */
    public AuthorizationDecisionController(
            AuthorizationDecisionService decisionService,
            InternalWorkloadIdentityVerifier workloadIdentityVerifier,
            InternalWorkloadRateLimiter workloadRateLimiter,
            SecurityAuditService auditService,
            ClientAddressResolver clientAddressResolver,
            AuthorizationMetrics metrics) {
        this.decisionService = decisionService;
        this.workloadIdentityVerifier = workloadIdentityVerifier;
        this.workloadRateLimiter = workloadRateLimiter;
        this.auditService = auditService;
        this.clientAddressResolver = clientAddressResolver;
        this.metrics = metrics;
    }

    /**
     * Returns one deterministic authorization decision after authenticating the workload caller.
     *
     * @param request internal decision request with protected-service-loaded resource facts
     * @param servletRequest source HTTP request
     * @return allow or deny decision
     */
    @PostMapping(
            value = "/api/v1/internal/authorization/decisions",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public AuthorizationDecision decide(
            @RequestBody AuthorizationRequest request,
            HttpServletRequest servletRequest) {
        authenticateWorkload(servletRequest);
        AuthorizationDecisionEvaluation evaluation = decisionService.decideForAudit(request);
        AuthorizationDecision decision = evaluation.decision();
        metrics.record(decision);
        recordDecision(decision, evaluation.verifiedSubjectId(), servletRequest);
        return decision;
    }

    private void authenticateWorkload(HttpServletRequest request) {
        if (InternalAuthorizationRequestFilter.verifiedWorkloadIdentity(request) == null) {
            String workloadIdentity = workloadIdentityVerifier.verify(request);
            workloadRateLimiter.check(workloadIdentity);
        }
    }

    private void recordDecision(
            AuthorizationDecision decision,
            java.util.UUID subjectId,
            HttpServletRequest request) {
        SecurityAuditEventType eventType = switch (decision.outcome()) {
            case ALLOW -> SecurityAuditEventType.AUTHORIZATION_ALLOWED;
            case DENY -> "POLICY_UNAVAILABLE".equals(decision.reasonCode())
                    ? SecurityAuditEventType.AUTHORIZATION_DEPENDENCY_UNAVAILABLE
                    : SecurityAuditEventType.AUTHORIZATION_DENIED;
        };
        try {
            auditService.recordAuthorizationOutcome(
                    eventType,
                    subjectId,
                    clientAddressResolver.resolve(request),
                    decision.reasonCode());
        } catch (RuntimeException auditFailure) {
            log.atError()
                    .addKeyValue("event", "authorization_audit_unavailable")
                    .addKeyValue("dependencyException", auditFailure.getClass().getName())
                    .log("Authorization audit persistence failed");
            throw new AuthorizationDependencyUnavailableException(auditFailure);
        }
    }
}
