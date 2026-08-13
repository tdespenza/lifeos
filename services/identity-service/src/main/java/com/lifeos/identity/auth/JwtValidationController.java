package com.lifeos.identity.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Small internal validation boundary for protected services during migration to a shared verifier
 * library.
 */
@RestController
public class JwtValidationController {

    private final JwtValidationService validationService;
    private final InternalWorkloadIdentityVerifier workloadIdentityVerifier;
    private final InternalWorkloadRateLimiter workloadRateLimiter;

    /**
     * Creates the protected-service validation boundary.
     *
     * @param validationService durable JWT/session validator
     * @param workloadIdentityVerifier authenticated internal-workload verifier
     * @param workloadRateLimiter distributed workload request limiter
     */
    public JwtValidationController(
            JwtValidationService validationService,
            InternalWorkloadIdentityVerifier workloadIdentityVerifier,
            InternalWorkloadRateLimiter workloadRateLimiter) {
        this.validationService = validationService;
        this.workloadIdentityVerifier = workloadIdentityVerifier;
        this.workloadRateLimiter = workloadRateLimiter;
    }

    /**
     * Validates one bearer token after applying the bounded validation limiter.
     *
     * @param request servlet request containing the authorization header
     * @return authenticated subject identifiers
     */
    @GetMapping("/api/v1/auth/validate")
    public Map<String, Object> validate(HttpServletRequest request) {
        String workloadIdentity = workloadIdentityVerifier.verify(request);
        workloadRateLimiter.check(workloadIdentity);
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new AuthenticationFailureException();
        }
        AuthenticatedSubject subject = validationService.validate(header.substring(7).trim());
        return Map.of(
                "accountId", subject.accountId(),
                "sessionId", subject.sessionId(),
                "authenticationMethod", subject.authenticationMethod(),
                "accessTokenProof", subject.accessTokenProof());
    }
}
