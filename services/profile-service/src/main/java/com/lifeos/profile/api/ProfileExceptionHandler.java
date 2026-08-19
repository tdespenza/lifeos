package com.lifeos.profile.api;

import com.lifeos.profile.audit.ProfileAuditUnavailableException;
import com.lifeos.profile.config.ProfilePayloadTooLargeException;
import com.lifeos.profile.authorization.ProfileAuthenticationFailure;
import com.lifeos.profile.authorization.ProfileAuthorizationDenied;
import com.lifeos.profile.authorization.ProfileAuthorizationDependencyUnavailable;
import com.lifeos.profile.idempotency.InvalidProfileCreatePreconditionException;
import com.lifeos.profile.idempotency.InvalidProfileIdempotencyKeyException;
import com.lifeos.profile.idempotency.InvalidProfileVersionPreconditionException;
import com.lifeos.profile.idempotency.ProfileCreatePreconditionRequiredException;
import com.lifeos.profile.idempotency.ProfileIdempotencyConflictException;
import com.lifeos.profile.idempotency.ProfileIdempotencyUnavailableException;
import com.lifeos.profile.idempotency.ProfileVersionPreconditionRequiredException;
import com.lifeos.profile.journal.JournalConflictException;
import com.lifeos.profile.journal.JournalNotFoundException;
import com.lifeos.profile.journal.JournalUnavailableException;
import com.lifeos.profile.service.HouseholdMemberConflictException;
import com.lifeos.profile.service.ProfileAlreadyExistsException;
import com.lifeos.profile.service.ProfileResourceNotFoundException;
import com.lifeos.profile.service.ProfileVersionConflictException;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;

/** Maps profile security and concurrency failures to stable, non-enumerating public responses. */
@RestControllerAdvice
public class ProfileExceptionHandler {

    @ExceptionHandler(ProfileAuthenticationFailure.class)
    public ResponseEntity<Map<String, String>> authenticationFailure(ProfileAuthenticationFailure exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .body(Map.of("error", "Authentication required"));
    }

    @ExceptionHandler(ProfileAuthorizationDenied.class)
    public ResponseEntity<Map<String, String>> authorizationDenied(ProfileAuthorizationDenied exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Access denied"));
    }

    @ExceptionHandler({ProfileAuthorizationDependencyUnavailable.class, ProfileAuditUnavailableException.class})
    public ResponseEntity<Map<String, String>> authorizationUnavailable(RuntimeException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(Map.of("error", "Authorization temporarily unavailable"));
    }

    @ExceptionHandler(ProfileResourceNotFoundException.class)
    public ResponseEntity<Map<String, String>> resourceUnavailable(ProfileResourceNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Resource not found"));
    }

    @ExceptionHandler({ProfileAlreadyExistsException.class, ProfileVersionConflictException.class})
    public ResponseEntity<Map<String, String>> preconditionFailed(RuntimeException exception) {
        return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED)
                .body(Map.of("error", "Profile representation is no longer current"));
    }

    @ExceptionHandler(ProfileVersionPreconditionRequiredException.class)
    public ResponseEntity<Map<String, String>> missingVersionPrecondition(
            ProfileVersionPreconditionRequiredException exception) {
        return ResponseEntity.status(428).body(Map.of("error", "If-Match is required for this mutation"));
    }

    @ExceptionHandler(ProfileCreatePreconditionRequiredException.class)
    public ResponseEntity<Map<String, String>> missingCreatePrecondition(
            ProfileCreatePreconditionRequiredException exception) {
        return ResponseEntity.status(428).body(Map.of("error", "If-None-Match: * is required for creation"));
    }

    @ExceptionHandler({InvalidProfileVersionPreconditionException.class, InvalidProfileCreatePreconditionException.class})
    public ResponseEntity<Map<String, String>> invalidPrecondition(RuntimeException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", "A valid conditional request header is required"));
    }

    @ExceptionHandler(InvalidProfileIdempotencyKeyException.class)
    public ResponseEntity<Map<String, String>> invalidIdempotencyKey(InvalidProfileIdempotencyKeyException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", "A valid Idempotency-Key header is required"));
    }

    @ExceptionHandler(ProfileIdempotencyConflictException.class)
    public ResponseEntity<Map<String, String>> idempotencyConflict(ProfileIdempotencyConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "Idempotency key conflicts with an existing request"));
    }

    @ExceptionHandler(ProfileIdempotencyUnavailableException.class)
    public ResponseEntity<Map<String, String>> idempotencyUnavailable(ProfileIdempotencyUnavailableException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(Map.of("error", "Idempotency request is temporarily unavailable"));
    }

    @ExceptionHandler(HouseholdMemberConflictException.class)
    public ResponseEntity<Map<String, String>> householdMemberConflict(HouseholdMemberConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Household membership conflict"));
    }

    @ExceptionHandler(JournalNotFoundException.class)
    public ResponseEntity<Map<String, String>> journalNotFound(JournalNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Resource not found"));
    }

    @ExceptionHandler(JournalConflictException.class)
    public ResponseEntity<Map<String, String>> journalConflict(JournalConflictException exception) {
        return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED)
                .body(Map.of("error", "Journal representation is no longer current"));
    }

    @ExceptionHandler(JournalUnavailableException.class)
    public ResponseEntity<Map<String, String>> journalUnavailable(JournalUnavailableException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(Map.of("error", "Journal storage is temporarily unavailable"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadableRequest(HttpMessageNotReadableException exception) {
        if (hasCause(exception, ProfilePayloadTooLargeException.class)) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("error", "Request payload too large"));
        }
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid request"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalidInput(IllegalArgumentException exception) {
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
