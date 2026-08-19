package com.lifeos.finance.api;

import com.lifeos.finance.audit.FinanceAuditUnavailableException;
import com.lifeos.finance.authorization.FinanceAuthenticationFailure;
import com.lifeos.finance.authorization.FinanceAuthorizationDenied;
import com.lifeos.finance.authorization.FinanceAuthorizationDependencyUnavailable;
import com.lifeos.finance.config.FinancePayloadTooLargeException;
import com.lifeos.finance.idempotency.FinanceCreatePreconditionRequiredException;
import com.lifeos.finance.idempotency.FinanceIdempotencyConflictException;
import com.lifeos.finance.idempotency.FinanceIdempotencyUnavailableException;
import com.lifeos.finance.idempotency.FinanceMutationRejectedException;
import com.lifeos.finance.idempotency.FinanceVersionPreconditionRequiredException;
import com.lifeos.finance.idempotency.InvalidFinanceIdempotencyKeyException;
import com.lifeos.finance.idempotency.InvalidFinancePreconditionException;
import com.lifeos.finance.service.FinanceBudgetOverlapException;
import com.lifeos.finance.service.FinanceContributionConflictException;
import com.lifeos.finance.service.FinanceResourceNotFoundException;
import com.lifeos.finance.service.FinanceVersionConflictException;
import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Stable non-enumerating public error contract for Finance security and concurrency outcomes. */
@RestControllerAdvice
public class FinanceExceptionHandler {

    @ExceptionHandler(FinanceAuthenticationFailure.class)
    public ResponseEntity<Map<String, String>> authenticationFailure(FinanceAuthenticationFailure exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .body(Map.of("error", "Authentication required"));
    }

    @ExceptionHandler(FinanceAuthorizationDenied.class)
    public ResponseEntity<Map<String, String>> authorizationDenied(FinanceAuthorizationDenied exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Access denied"));
    }

    @ExceptionHandler({FinanceAuthorizationDependencyUnavailable.class, FinanceAuditUnavailableException.class})
    public ResponseEntity<Map<String, String>> authorizationUnavailable(RuntimeException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(Map.of("error", "Authorization temporarily unavailable"));
    }

    @ExceptionHandler(FinanceResourceNotFoundException.class)
    public ResponseEntity<Map<String, String>> resourceUnavailable(FinanceResourceNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Resource not found"));
    }

    @ExceptionHandler({FinanceBudgetOverlapException.class, FinanceVersionConflictException.class})
    public ResponseEntity<Map<String, String>> preconditionFailed(RuntimeException exception) {
        return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED)
                .body(Map.of("error", "Finance representation is no longer current"));
    }

    @ExceptionHandler(FinanceContributionConflictException.class)
    public ResponseEntity<Map<String, String>> contributionConflict(FinanceContributionConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Finance contribution conflicts"));
    }

    @ExceptionHandler(FinanceMutationRejectedException.class)
    public ResponseEntity<Map<String, String>> mutationRejected(FinanceMutationRejectedException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Finance mutation conflicts"));
    }

    @ExceptionHandler(FinanceVersionPreconditionRequiredException.class)
    public ResponseEntity<Map<String, String>> missingVersionPrecondition(
            FinanceVersionPreconditionRequiredException exception) {
        return ResponseEntity.status(428).body(Map.of("error", "If-Match is required for this mutation"));
    }

    @ExceptionHandler(FinanceCreatePreconditionRequiredException.class)
    public ResponseEntity<Map<String, String>> missingCreatePrecondition(
            FinanceCreatePreconditionRequiredException exception) {
        return ResponseEntity.status(428).body(Map.of("error", "If-None-Match: * is required for creation"));
    }

    @ExceptionHandler({InvalidFinancePreconditionException.class, InvalidFinanceIdempotencyKeyException.class})
    public ResponseEntity<Map<String, String>> invalidConditionalRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", "A valid conditional request header is required"));
    }

    @ExceptionHandler(FinanceIdempotencyConflictException.class)
    public ResponseEntity<Map<String, String>> idempotencyConflict(FinanceIdempotencyConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "Idempotency key conflicts with an existing request"));
    }

    @ExceptionHandler(FinanceIdempotencyUnavailableException.class)
    public ResponseEntity<Map<String, String>> idempotencyUnavailable(FinanceIdempotencyUnavailableException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(Map.of("error", "Idempotency request is temporarily unavailable"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadableRequest(HttpMessageNotReadableException exception) {
        if (hasCause(exception, FinancePayloadTooLargeException.class)) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("error", "Request payload too large"));
        }
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid request"));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, String>> invalidInput(Exception exception) {
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid request"));
    }

    private static boolean hasCause(Throwable exception, Class<? extends Throwable> expectedType) {
        Throwable current = exception;
        while (current != null) {
            if (expectedType.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
