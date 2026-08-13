package com.lifeos.identity.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.converter.HttpMessageNotReadableException;

/**
 * Versioned REST boundaries for passwordless WebAuthn authentication.
 */
@RestController
public class PasskeyController {

    private static final String PASSKEY_FAILURE = "The passkey authentication request could not be completed.";
    private static final String TEMPORARY_FAILURE = "Authentication is temporarily unavailable.";
    private static final String CAPACITY_FAILURE = "The account cannot create another active session.";

    private final PasskeyAuthenticationService authenticationService;
    private final ClientAddressResolver clientAddressResolver;

    /**
     * Creates a controller with default address resolution for package-local MVC tests only.
     *
     * @param authenticationService passkey authentication service
     */
    PasskeyController(PasskeyAuthenticationService authenticationService) {
        this(authenticationService, new ClientAddressResolver(new IdentityAuthProperties()));
    }

    /**
     * Creates the passkey controller with trusted-proxy-aware address resolution.
     *
     * @param authenticationService passkey authentication service
     * @param clientAddressResolver client address resolver
     */
    @org.springframework.beans.factory.annotation.Autowired
    public PasskeyController(
            PasskeyAuthenticationService authenticationService,
            ClientAddressResolver clientAddressResolver) {
        this.authenticationService = authenticationService;
        this.clientAddressResolver = clientAddressResolver;
    }

    /**
     * Starts a username-less passkey ceremony.
     *
     * @param servletRequest request used only for keyed audit fingerprinting
     * @return public-key request options and opaque challenge handle
     */
    @PostMapping(value = "/api/v1/auth/passkey/options", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PasskeyAuthenticationOptions> options(HttpServletRequest servletRequest) {
        return ResponseEntity.ok(authenticationService.begin(clientAddressResolver.resolve(servletRequest)));
    }

    /**
     * Completes a passkey ceremony and returns the shared LifeOS session/token result.
     *
     * @param request challenge handle and browser assertion
     * @param servletRequest request used only for keyed audit fingerprinting
     * @return shared session/token response
     */
    @PostMapping(value = "/api/v1/auth/passkey/assertion", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LoginResponse> assertion(
            @Valid @RequestBody PasskeyAuthenticationRequest request,
            HttpServletRequest servletRequest) {
        String clientAddress = clientAddressResolver.resolve(servletRequest);
        String userAgent = servletRequest.getHeader(HttpHeaders.USER_AGENT);
        LoginResponse response = userAgent == null || userAgent.isBlank()
                ? authenticationService.complete(request, clientAddress)
                : authenticationService.complete(
                        request, clientAddress, DeviceMetadataResolver.fromUserAgent(userAgent));
        ResponseEntity.BodyBuilder responseBuilder = ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store");
        var cookie = RefreshCookieSupport.from(response);
        if (cookie != null) {
            responseBuilder.header(HttpHeaders.SET_COOKIE, cookie.toString());
        }
        return responseBuilder.body(response);
    }

    @ExceptionHandler(AuthenticationFailureException.class)
    public ResponseEntity<ProblemDetail> handleAuthenticationFailure() {
        return problem(HttpStatus.UNAUTHORIZED, PASSKEY_FAILURE);
    }

    @ExceptionHandler(AuthenticationDependencyUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleDependencyFailure() {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, TEMPORARY_FAILURE);
    }

    /**
     * Returns the bounded retry delay when the passkey client exceeds the shared attempt limit.
     *
     * @param exception rate-limit exception
     * @return sanitized 429 response
     */
    @ExceptionHandler(LoginRateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleRateLimit(LoginRateLimitExceededException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(exception.getRetryAfterSeconds()))
                .body(problemDetail(HttpStatus.TOO_MANY_REQUESTS,
                        "Authentication attempts are temporarily limited."));
    }

    @ExceptionHandler(SessionCapacityExceededException.class)
    public ResponseEntity<ProblemDetail> handleSessionCapacity() {
        return problem(HttpStatus.CONFLICT, CAPACITY_FAILURE);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ProblemDetail> handleInvalidRequest() {
        return problem(HttpStatus.BAD_REQUEST, PASSKEY_FAILURE);
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(problemDetail(status, detail));
    }

    private ProblemDetail problemDetail(HttpStatus status, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle("Authentication request failed");
        return problem;
    }
}
