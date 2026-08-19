package com.lifeos.calendar.api;

import com.lifeos.calendar.audit.CalendarAuditUnavailableException;
import com.lifeos.calendar.authorization.CalendarAuthenticationFailure;
import com.lifeos.calendar.authorization.CalendarAuthorizationDependencyUnavailable;
import com.lifeos.calendar.authorization.CalendarAuthorizationDenied;
import com.lifeos.calendar.config.CalendarPayloadTooLargeException;
import com.lifeos.calendar.domain.CalendarLifecycleTransitionException;
import com.lifeos.calendar.idempotency.CalendarIdempotencyConflictException;
import com.lifeos.calendar.idempotency.CalendarIdempotencyUnavailableException;
import com.lifeos.calendar.idempotency.CalendarVersionPreconditionRequiredException;
import com.lifeos.calendar.idempotency.InvalidCalendarIdempotencyKeyException;
import com.lifeos.calendar.idempotency.InvalidCalendarVersionPreconditionException;
import com.lifeos.calendar.service.CalendarConflictException;
import com.lifeos.calendar.service.CalendarConflictResultTooLargeException;
import com.lifeos.calendar.service.CalendarResourceNotFoundException;
import com.lifeos.calendar.service.CalendarVersionConflictException;
import com.lifeos.calendar.service.UnsupportedCalendarLinkException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Stable generic public errors that preserve Calendar's ownership and privacy boundary. */
@RestControllerAdvice
public class CalendarExceptionHandler {

    @ExceptionHandler(CalendarAuthenticationFailure.class)
    public ResponseEntity<Map<String, String>> authentication(CalendarAuthenticationFailure exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .body(Map.of("error", "Authentication required"));
    }

    @ExceptionHandler(CalendarAuthorizationDenied.class)
    public ResponseEntity<Map<String, String>> denied(CalendarAuthorizationDenied exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Access denied"));
    }

    @ExceptionHandler({CalendarAuthorizationDependencyUnavailable.class, CalendarAuditUnavailableException.class})
    public ResponseEntity<Map<String, String>> dependencyUnavailable(RuntimeException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(Map.of("error", "Calendar dependency temporarily unavailable"));
    }

    @ExceptionHandler(CalendarResourceNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(CalendarResourceNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Resource not found"));
    }

    @ExceptionHandler(CalendarVersionConflictException.class)
    public ResponseEntity<Map<String, String>> versionConflict(CalendarVersionConflictException exception) {
        return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED)
                .body(Map.of("error", "Calendar representation is no longer current"));
    }

    @ExceptionHandler(CalendarLifecycleTransitionException.class)
    public ResponseEntity<Map<String, String>> invalidLifecycleTransition(CalendarLifecycleTransitionException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "Calendar lifecycle transition is not valid"));
    }

    @ExceptionHandler(CalendarVersionPreconditionRequiredException.class)
    public ResponseEntity<Map<String, String>> missingVersion(CalendarVersionPreconditionRequiredException exception) {
        return ResponseEntity.status(428).body(Map.of("error", "If-Match is required for this mutation"));
    }

    @ExceptionHandler(InvalidCalendarVersionPreconditionException.class)
    public ResponseEntity<Map<String, String>> invalidVersion(InvalidCalendarVersionPreconditionException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", "A valid strong If-Match header is required"));
    }

    @ExceptionHandler(InvalidCalendarIdempotencyKeyException.class)
    public ResponseEntity<Map<String, String>> invalidIdempotency(InvalidCalendarIdempotencyKeyException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", "A valid Idempotency-Key header is required"));
    }

    @ExceptionHandler(CalendarIdempotencyConflictException.class)
    public ResponseEntity<Map<String, String>> idempotencyConflict(CalendarIdempotencyConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "Idempotency key conflicts with an existing request"));
    }

    @ExceptionHandler(CalendarIdempotencyUnavailableException.class)
    public ResponseEntity<Map<String, String>> idempotencyUnavailable(CalendarIdempotencyUnavailableException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(Map.of("error", "Idempotency request is temporarily unavailable"));
    }

    @ExceptionHandler(CalendarConflictException.class)
    public ResponseEntity<List<CalendarConflictResponse>> conflict(CalendarConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(exception.getConflicts().stream().map(CalendarConflictResponse::from).toList());
    }

    @ExceptionHandler(CalendarConflictResultTooLargeException.class)
    public ResponseEntity<Map<String, String>> conflictResultTooLarge(CalendarConflictResultTooLargeException exception) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("error", "Calendar window contains too many commitments; request a smaller range"));
    }

    @ExceptionHandler(UnsupportedCalendarLinkException.class)
    public ResponseEntity<Map<String, String>> unsupportedLink(UnsupportedCalendarLinkException exception) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("error", "Task and Goal ownership could not be verified"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadable(HttpMessageNotReadableException exception) {
        if (hasCause(exception, CalendarPayloadTooLargeException.class)) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("error", "Request payload too large"));
        }
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid request"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalidInput(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid request"));
    }

    private static boolean hasCause(Throwable exception, Class<? extends Throwable> expected) {
        Throwable current = exception;
        while (current != null) {
            if (expected.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
