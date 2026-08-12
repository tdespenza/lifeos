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
    private final LoginRateLimiter rateLimiter;
    private final ClientAddressResolver clientAddressResolver;

    /**
     * Creates the protected-service validation boundary.
     *
     * @param validationService durable JWT/session validator
     * @param rateLimiter distributed request limiter
     * @param clientAddressResolver trusted client-address resolver
     */
    public JwtValidationController(
            JwtValidationService validationService,
            LoginRateLimiter rateLimiter,
            ClientAddressResolver clientAddressResolver) {
        this.validationService = validationService;
        this.rateLimiter = rateLimiter;
        this.clientAddressResolver = clientAddressResolver;
    }

    /**
     * Validates one bearer token after applying the bounded validation limiter.
     *
     * @param request servlet request containing the authorization header
     * @return authenticated subject identifiers
     */
    @GetMapping("/api/v1/auth/validate")
    public Map<String, Object> validate(HttpServletRequest request) {
        rateLimiter.check("jwt-validation", clientAddressResolver.resolve(request));
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new AuthenticationFailureException();
        }
        AuthenticatedSubject subject = validationService.validate(header.substring(7).trim());
        return Map.of(
                "accountId", subject.accountId(),
                "sessionId", subject.sessionId(),
                "authenticationMethod", subject.authenticationMethod());
    }
}
