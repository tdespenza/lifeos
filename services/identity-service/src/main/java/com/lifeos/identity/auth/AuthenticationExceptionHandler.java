package com.lifeos.identity.auth;

import com.lifeos.identity.account.UserAccountController;
import com.lifeos.identity.authorization.AuthorizationDecisionController;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;

/** Sanitized errors for authentication controllers outside the login controller. */
@RestControllerAdvice(assignableTypes = {
        UserAccountController.class,
        RefreshController.class,
        OidcController.class,
        PasskeyController.class,
        JwtValidationController.class,
        AuthorizationDecisionController.class,
        SessionController.class
})
public class AuthenticationExceptionHandler {

    /**
     * Maps sanitized credential failures to 401.
     *
     * @return generic unauthorized problem detail
     */
    @ExceptionHandler(AuthenticationFailureException.class)
    public ResponseEntity<ProblemDetail> authenticationFailure() {
        return problem(HttpStatus.UNAUTHORIZED, "Authentication failed.");
    }

    /**
     * Maps unavailable authentication dependencies to 503.
     *
     * @return generic temporary-failure problem detail
     */
    @ExceptionHandler(AuthenticationDependencyUnavailableException.class)
    public ResponseEntity<ProblemDetail> dependencyFailure() {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Authentication is temporarily unavailable.");
    }

    /**
     * Maps a missing, unknown, or mismatched internal workload to one generic unauthorized
     * response. The same shape prevents external callers from distinguishing configured service
     * identities or credentials.
     *
     * @return generic internal-caller failure
     */
    @ExceptionHandler(InternalWorkloadAuthenticationException.class)
    public ResponseEntity<ProblemDetail> internalWorkloadFailure() {
        return problem(HttpStatus.UNAUTHORIZED, "Internal authorization request failed.",
                "Internal authorization request failed");
    }

    /**
     * Maps a required authorization audit or policy dependency failure to a safe temporary error.
     *
     * @return generic authorization dependency failure
     */
    @ExceptionHandler(AuthorizationDependencyUnavailableException.class)
    public ResponseEntity<ProblemDetail> authorizationDependencyFailure() {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Authorization is temporarily unavailable.",
                "Internal authorization request failed");
    }

    /**
     * Maps validation-endpoint throttling to 429.
     *
     * @param exception rate-limit exception
     * @return generic rate-limit problem detail
     */
    @ExceptionHandler(LoginRateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> rateLimit(LoginRateLimitExceededException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(exception.getRetryAfterSeconds()))
                .body(problemDetail(HttpStatus.TOO_MANY_REQUESTS,
                        "Authentication attempts are temporarily limited."));
    }

    /**
     * Maps authenticated-workload rate limiting to a generic retryable result.
     *
     * @param exception workload limit result
     * @return generic rate-limit problem detail
     */
    @ExceptionHandler(InternalWorkloadRateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> internalWorkloadRateLimit(
            InternalWorkloadRateLimitExceededException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(exception.getRetryAfterSeconds()))
                .body(problemDetail(HttpStatus.TOO_MANY_REQUESTS,
                        "Internal authorization requests are temporarily limited."));
    }

    /**
     * Maps malformed JSON to a generic 400 response.
     *
     * @return generic bad-request problem detail
     */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> unreadableRequest() {
        return problem(HttpStatus.BAD_REQUEST, "Authentication request failed.");
    }

    /**
     * Maps authentication request validation failures to 400.
     *
     * @return generic bad-request problem detail
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> validationFailure() {
        return problem(HttpStatus.BAD_REQUEST, "Authentication request failed.");
    }

    /**
     * Maps malformed session cursors, UUIDs, and page bounds without echoing caller input.
     *
     * @return generic bad-request problem detail
     */
    @ExceptionHandler(SessionRequestValidationException.class)
    public ResponseEntity<ProblemDetail> sessionValidationFailure() {
        return problem(HttpStatus.BAD_REQUEST, "Session request failed.");
    }

    /**
     * Maps malformed or missing request parameters to the generic authentication 400 response.
     *
     * @return generic bad-request problem detail
     */
    @ExceptionHandler({
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
        org.springframework.web.bind.MissingServletRequestParameterException.class
    })
    public ResponseEntity<ProblemDetail> parameterFailure() {
        return problem(HttpStatus.BAD_REQUEST, "Authentication request failed.");
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(problemDetail(status, detail));
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail, String title) {
        ProblemDetail problem = problemDetail(status, detail);
        problem.setTitle(title);
        return ResponseEntity.status(status).body(problem);
    }

    private ProblemDetail problemDetail(HttpStatus status, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle("Authentication request failed");
        return problem;
    }
}
