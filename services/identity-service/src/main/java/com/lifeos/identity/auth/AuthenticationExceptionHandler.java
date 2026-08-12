package com.lifeos.identity.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;

/** Sanitized errors for authentication controllers outside the login controller. */
@RestControllerAdvice
public class AuthenticationExceptionHandler {

    @ExceptionHandler(AuthenticationFailureException.class)
    public ResponseEntity<ProblemDetail> authenticationFailure() {
        return problem(HttpStatus.UNAUTHORIZED, "Authentication failed.");
    }

    @ExceptionHandler(AuthenticationDependencyUnavailableException.class)
    public ResponseEntity<ProblemDetail> dependencyFailure() {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Authentication is temporarily unavailable.");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> validationFailure() {
        return problem(HttpStatus.BAD_REQUEST, "Authentication request failed.");
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle("Authentication request failed");
        return ResponseEntity.status(status).body(problem);
    }
}
