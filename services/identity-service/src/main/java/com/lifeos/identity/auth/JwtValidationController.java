package com.lifeos.identity.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Small internal validation boundary useful to protected services during the migration to a shared verifier library. */
@RestController
public class JwtValidationController {

    private final JwtValidationService validationService;

    public JwtValidationController(JwtValidationService validationService) {
        this.validationService = validationService;
    }

    @GetMapping("/api/v1/auth/validate")
    public Map<String, Object> validate(HttpServletRequest request) {
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
