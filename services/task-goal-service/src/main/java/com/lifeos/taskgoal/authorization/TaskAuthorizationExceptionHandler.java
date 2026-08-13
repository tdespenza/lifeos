package com.lifeos.taskgoal.authorization;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps security-boundary failures to deliberately generic client responses. */
@RestControllerAdvice
public class TaskAuthorizationExceptionHandler {

    private static final String AUTHORIZATION_RETRY_AFTER_SECONDS = "1";

    private static final AuthorizationErrorResponse AUTHENTICATION_FAILURE =
            new AuthorizationErrorResponse("Authentication required");
    private static final AuthorizationErrorResponse AUTHORIZATION_DENIED =
            new AuthorizationErrorResponse("Access denied");
    private static final AuthorizationErrorResponse AUTHORIZATION_UNAVAILABLE =
            new AuthorizationErrorResponse("Authorization temporarily unavailable");

    @ExceptionHandler(TaskAuthenticationFailure.class)
    public ResponseEntity<AuthorizationErrorResponse> authenticationFailure(TaskAuthenticationFailure exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .body(AUTHENTICATION_FAILURE);
    }

    @ExceptionHandler(TaskAuthorizationDenied.class)
    public ResponseEntity<AuthorizationErrorResponse> authorizationDenied(TaskAuthorizationDenied exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(AUTHORIZATION_DENIED);
    }

    @ExceptionHandler(TaskAuthorizationDependencyUnavailable.class)
    public ResponseEntity<AuthorizationErrorResponse> authorizationUnavailable(
            TaskAuthorizationDependencyUnavailable exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, AUTHORIZATION_RETRY_AFTER_SECONDS)
                .body(AUTHORIZATION_UNAVAILABLE);
    }

    public record AuthorizationErrorResponse(String error) {
    }
}
