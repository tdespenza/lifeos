package com.lifeos.identity.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Versioned REST boundary for first-party email/password login.
 */
@RestController
public class LoginController {

    private static final String GENERIC_FAILURE = "The supplied credentials could not be verified.";
    private static final String TEMPORARY_FAILURE = "Authentication is temporarily unavailable.";

    private final LoginService loginService;
    private final ClientAddressResolver clientAddressResolver;

    /**
     * Creates the login controller.
     *
     * @param loginService login application service
     */
    public LoginController(LoginService loginService) {
        this(loginService, new ClientAddressResolver(new IdentityAuthProperties()));
    }

    /**
     * Creates the login controller with trusted-proxy-aware client address resolution.
     *
     * @param loginService login application service
     * @param clientAddressResolver client address resolver
     */
    @org.springframework.beans.factory.annotation.Autowired
    public LoginController(LoginService loginService, ClientAddressResolver clientAddressResolver) {
        this.loginService = loginService;
        this.clientAddressResolver = clientAddressResolver;
    }

    /**
     * Authenticates first-party credentials and returns the shared session/token result.
     *
     * @param request validated credential request
     * @param servletRequest servlet request used for the bounded client fingerprint
     * @return signed access-token result
     */
    @PostMapping("/api/v1/auth/login")
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        LoginResponse response = loginService.login(request, clientAddressResolver.resolve(servletRequest));
        ResponseEntity.BodyBuilder responseBuilder = ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store");
        var cookie = RefreshCookieSupport.from(response);
        if (cookie != null) {
            responseBuilder.header(HttpHeaders.SET_COOKIE, cookie.toString());
        }
        return responseBuilder.body(response);
    }

    /**
     * Returns the same generic credential failure for unknown, wrong, disabled, or missing
     * credentials.
     *
     * @return sanitized 401 problem detail
     */
    @ExceptionHandler(AuthenticationFailureException.class)
    public ResponseEntity<ProblemDetail> handleAuthenticationFailure() {
        return problem(HttpStatus.UNAUTHORIZED, GENERIC_FAILURE);
    }

    /**
     * Returns a bounded response when distributed rate limiting rejects the attempt.
     *
     * @param exception rate-limit exception
     * @return sanitized 429 response with retry delay
     */
    @ExceptionHandler(LoginRateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleRateLimit(LoginRateLimitExceededException exception) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(exception.getRetryAfterSeconds()));
        return response.body(problemDetail(HttpStatus.TOO_MANY_REQUESTS,
                "Authentication attempts are temporarily limited."));
    }

    /**
     * Returns a generic temporary failure when a safe distributed decision cannot be made.
     *
     * @return sanitized 503 problem detail
     */
    @ExceptionHandler(AuthenticationDependencyUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleDependencyFailure() {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, TEMPORARY_FAILURE);
    }

    /**
     * Returns a generic conflict when the account session cap is reached.
     *
     * @return sanitized 409 problem detail
     */
    @ExceptionHandler(SessionCapacityExceededException.class)
    public ResponseEntity<ProblemDetail> handleSessionCapacity() {
        return problem(HttpStatus.CONFLICT, "The account cannot create another active session.");
    }

    /**
     * Prevents validation details from echoing credential input or account state.
     *
     * @return sanitized 400 problem detail
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidationFailure() {
        return problem(HttpStatus.BAD_REQUEST, GENERIC_FAILURE);
    }

    /**
     * Builds a sanitized RFC 9457 problem detail response.
     *
     * @param status HTTP status
     * @param detail safe client-facing detail
     * @return response containing problem detail
     */
    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(problemDetail(status, detail));
    }

    /**
     * Creates a problem detail without request data.
     *
     * @param status HTTP status
     * @param detail safe client-facing detail
     * @return problem detail
     */
    private ProblemDetail problemDetail(HttpStatus status, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle("Authentication request failed");
        return problem;
    }
}
