package com.lifeos.notification.access;

import com.lifeos.notification.endpoint.EndpointIdempotencyConflictException;
import com.lifeos.notification.endpoint.EndpointIdempotencyUnavailableException;
import com.lifeos.notification.endpoint.EndpointNotFoundException;
import com.lifeos.notification.endpoint.InvalidEndpointIdempotencyKeyException;
import com.lifeos.notification.stream.NotificationStreamCapacityExceededException;
import com.lifeos.notification.stream.NotificationStreamResyncRequiredException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Generic public failures that never include bearer, identity, or provider exception details. */
@RestControllerAdvice
public class NotificationAccessExceptionHandler {

    @ExceptionHandler(NotificationAuthenticationFailure.class)
    public ResponseEntity<ProblemDetail> handleAuthenticationFailure() {
        return problem(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "A valid bearer credential is required");
    }

    @ExceptionHandler(NotificationAuthenticationDependencyUnavailable.class)
    public ResponseEntity<ProblemDetail> handleDependencyUnavailable() {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "AUTHENTICATION_UNAVAILABLE", "Authentication is unavailable");
    }

    @ExceptionHandler({IllegalArgumentException.class, InvalidEndpointIdempotencyKeyException.class})
    public ResponseEntity<ProblemDetail> handleInvalidRequest() {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_NOTIFICATION_REQUEST", "Notification request is not valid");
    }

    @ExceptionHandler(EndpointIdempotencyConflictException.class)
    public ResponseEntity<ProblemDetail> handleIdempotencyConflict() {
        return problem(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "Idempotency key conflicts with an existing request");
    }

    @ExceptionHandler(EndpointIdempotencyUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleIdempotencyUnavailable() {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "IDEMPOTENCY_UNAVAILABLE", "Retry shortly");
    }

    @ExceptionHandler(EndpointNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleEndpointNotFound() {
        return problem(HttpStatus.NOT_FOUND, "NOTIFICATION_ENDPOINT_NOT_FOUND", "Notification endpoint is not available");
    }

    @ExceptionHandler(NotificationStreamCapacityExceededException.class)
    public ResponseEntity<ProblemDetail> handleStreamCapacity() {
        return problem(HttpStatus.TOO_MANY_REQUESTS, "STREAM_CAPACITY_EXCEEDED", "Retry shortly");
    }

    @ExceptionHandler(NotificationStreamResyncRequiredException.class)
    public ResponseEntity<ProblemDetail> handleStreamResync() {
        return problem(HttpStatus.CONFLICT, "STREAM_RESYNC_REQUIRED", "Resynchronize through notification history");
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setProperty("code", code);
        return ResponseEntity.status(status).body(body);
    }
}
