package com.lifeos.identity.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Explicit passkey recovery-code generation and one-time recovery login boundary. */
@RestController
public class PasskeyRecoveryController {

    private static final String FAILURE = "The passkey recovery request could not be completed.";
    private static final String TEMPORARY_FAILURE = "Authentication is temporarily unavailable.";

    private final PasskeyRecoveryService recoveryService;
    private final JwtValidationService validationService;
    private final ClientAddressResolver clientAddressResolver;

    public PasskeyRecoveryController(
            PasskeyRecoveryService recoveryService,
            JwtValidationService validationService,
            ClientAddressResolver clientAddressResolver) {
        this.recoveryService = recoveryService;
        this.validationService = validationService;
        this.clientAddressResolver = clientAddressResolver;
    }

    @PostMapping(value = "/api/v1/auth/passkey/recovery-codes", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PasskeyRecoveryResult> generate(HttpServletRequest request) {
        String clientAddress = clientAddressResolver.resolve(request);
        PasskeyRecoveryResult result = recoveryService.generate(authenticate(request), clientAddress);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(result);
    }

    @PostMapping(value = "/api/v1/auth/passkey/recover", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LoginResponse> recover(
            @Valid @RequestBody PasskeyRecoveryRequest body, HttpServletRequest request) {
        String clientAddress = clientAddressResolver.resolve(request);
        LoginResponse response = recoveryService.recover(
                body, clientAddress, DeviceMetadataResolver.fromUserAgent(request.getHeader(HttpHeaders.USER_AGENT)));
        ResponseEntity.BodyBuilder responseBuilder = ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store");
        var cookie = RefreshCookieSupport.from(response);
        if (cookie != null) {
            responseBuilder.header(HttpHeaders.SET_COOKIE, cookie.toString());
        }
        return responseBuilder.body(response);
    }

    @ExceptionHandler({AuthenticationFailureException.class, PasskeyCredentialNotFoundException.class})
    public ResponseEntity<ProblemDetail> authenticationFailure() {
        return problem(HttpStatus.UNAUTHORIZED, FAILURE);
    }

    @ExceptionHandler(LoginRateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> rateLimited(LoginRateLimitExceededException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(exception.getRetryAfterSeconds()))
                .body(problemDetail(HttpStatus.TOO_MANY_REQUESTS, "Authentication attempts are temporarily limited."));
    }

    @ExceptionHandler(AuthenticationDependencyUnavailableException.class)
    public ResponseEntity<ProblemDetail> temporaryFailure() {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, TEMPORARY_FAILURE);
    }

    @ExceptionHandler(SessionCapacityExceededException.class)
    public ResponseEntity<ProblemDetail> sessionCapacity() {
        return problem(HttpStatus.CONFLICT, "The account cannot create another active session.");
    }

    private AuthenticatedSubject authenticate(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new AuthenticationFailureException();
        }
        String token = header.substring(7).trim();
        if (token.isBlank() || token.chars().anyMatch(Character::isWhitespace)) {
            throw new AuthenticationFailureException();
        }
        return validationService.validate(token);
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(problemDetail(status, detail));
    }

    private ProblemDetail problemDetail(HttpStatus status, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle("Passkey recovery failed");
        return problem;
    }
}
