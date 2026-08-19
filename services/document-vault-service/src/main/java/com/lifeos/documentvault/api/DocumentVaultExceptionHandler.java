package com.lifeos.documentvault.api;

import com.lifeos.documentvault.audit.DocumentVaultAuditUnavailableException;
import com.lifeos.documentvault.authorization.DocumentVaultAuthenticationFailure;
import com.lifeos.documentvault.authorization.DocumentVaultAuthorizationDenied;
import com.lifeos.documentvault.authorization.DocumentVaultAuthorizationDependencyUnavailable;
import com.lifeos.documentvault.config.DocumentVaultPayloadTooLargeException;
import com.lifeos.documentvault.idempotency.DocumentIdempotencyConflictException;
import com.lifeos.documentvault.idempotency.DocumentIdempotencyUnavailableException;
import com.lifeos.documentvault.idempotency.DocumentVersionConflictException;
import com.lifeos.documentvault.idempotency.DocumentVersionPreconditionRequiredException;
import com.lifeos.documentvault.idempotency.InvalidDocumentIdempotencyKeyException;
import com.lifeos.documentvault.idempotency.InvalidDocumentVersionPreconditionException;
import com.lifeos.documentvault.service.DocumentResourceUnavailableException;
import com.lifeos.documentvault.storage.DocumentObjectStorageException;
import com.lifeos.documentvault.storage.DocumentUploadDeadlineExceededException;
import com.lifeos.documentvault.storage.DocumentUploadTooLargeException;
import com.lifeos.documentvault.storage.UnsupportedDocumentMediaTypeException;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/** Maps all rejected inputs to generic stable bodies, never provider paths or ownership facts. */
@RestControllerAdvice
public class DocumentVaultExceptionHandler {

    @ExceptionHandler(DocumentVaultAuthenticationFailure.class)
    public ResponseEntity<Map<String, String>> authenticationFailure(DocumentVaultAuthenticationFailure exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .body(Map.of("error", "Authentication required"));
    }

    @ExceptionHandler(DocumentVaultAuthorizationDenied.class)
    public ResponseEntity<Map<String, String>> authorizationDenied(DocumentVaultAuthorizationDenied exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Access denied"));
    }

    @ExceptionHandler({DocumentVaultAuthorizationDependencyUnavailable.class, DocumentVaultAuditUnavailableException.class})
    public ResponseEntity<Map<String, String>> authorizationUnavailable(RuntimeException exception) {
        return unavailable("Authorization temporarily unavailable");
    }

    @ExceptionHandler(DocumentResourceUnavailableException.class)
    public ResponseEntity<Map<String, String>> resourceUnavailable(DocumentResourceUnavailableException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Resource not found"));
    }

    @ExceptionHandler(DocumentVersionConflictException.class)
    public ResponseEntity<Map<String, String>> versionConflict(DocumentVersionConflictException exception) {
        return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED)
                .body(Map.of("error", "Document representation is no longer current"));
    }

    @ExceptionHandler(DocumentVersionPreconditionRequiredException.class)
    public ResponseEntity<Map<String, String>> missingVersionPrecondition(
            DocumentVersionPreconditionRequiredException exception) {
        return ResponseEntity.status(428).body(Map.of("error", "If-Match is required for metadata updates"));
    }

    @ExceptionHandler({InvalidDocumentIdempotencyKeyException.class, InvalidDocumentVersionPreconditionException.class})
    public ResponseEntity<Map<String, String>> invalidPrecondition(RuntimeException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", "A valid conditional request header is required"));
    }

    @ExceptionHandler(DocumentIdempotencyConflictException.class)
    public ResponseEntity<Map<String, String>> idempotencyConflict(DocumentIdempotencyConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "Idempotency key conflicts with an existing request"));
    }

    @ExceptionHandler({DocumentIdempotencyUnavailableException.class, DocumentObjectStorageException.class})
    public ResponseEntity<Map<String, String>> temporarilyUnavailable(RuntimeException exception) {
        return unavailable("Document operation is temporarily unavailable");
    }

    @ExceptionHandler({DocumentUploadTooLargeException.class, DocumentVaultPayloadTooLargeException.class,
            MaxUploadSizeExceededException.class})
    public ResponseEntity<Map<String, String>> payloadTooLarge(RuntimeException exception) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("error", "Request payload too large"));
    }

    @ExceptionHandler(UnsupportedDocumentMediaTypeException.class)
    public ResponseEntity<Map<String, String>> unsupportedMediaType(UnsupportedDocumentMediaTypeException exception) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(Map.of("error", "Document media type is not supported"));
    }

    @ExceptionHandler(DocumentUploadDeadlineExceededException.class)
    public ResponseEntity<Map<String, String>> uploadDeadlineExceeded(DocumentUploadDeadlineExceededException exception) {
        return ResponseEntity.status(HttpStatus.REQUEST_TIMEOUT).body(Map.of("error", "Document upload timed out"));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class,
            MethodArgumentTypeMismatchException.class, MissingServletRequestPartException.class,
            IllegalArgumentException.class})
    public ResponseEntity<Map<String, String>> invalidRequest(Exception exception) {
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid request"));
    }

    private static ResponseEntity<Map<String, String>> unavailable(String error) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(Map.of("error", error));
    }
}
