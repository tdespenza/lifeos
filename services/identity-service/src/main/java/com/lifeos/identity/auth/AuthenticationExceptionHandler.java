package com.lifeos.identity.auth;

import com.lifeos.identity.account.UserAccountController;
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
        JwtValidationController.class
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

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(problemDetail(status, detail));
    }

    private ProblemDetail problemDetail(HttpStatus status, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle("Authentication request failed");
        return problem;
    }
}
