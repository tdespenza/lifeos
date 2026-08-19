package com.lifeos.media.api;

import com.lifeos.media.audit.MediaAuditUnavailableException;
import com.lifeos.media.authorization.MediaAuthenticationFailure;
import com.lifeos.media.authorization.MediaAuthorizationDenied;
import com.lifeos.media.authorization.MediaAuthorizationDependencyUnavailable;
import com.lifeos.media.domain.MediaLifecycleTransitionException;
import com.lifeos.media.idempotency.InvalidMediaIdempotencyKeyException;
import com.lifeos.media.idempotency.InvalidMediaVersionPreconditionException;
import com.lifeos.media.idempotency.MediaIdempotencyConflictException;
import com.lifeos.media.idempotency.MediaIdempotencyUnavailableException;
import com.lifeos.media.idempotency.MediaVersionPreconditionRequiredException;
import com.lifeos.media.service.MediaResourceNotFoundException;
import com.lifeos.media.service.MediaSessionNotJoinableException;
import com.lifeos.media.service.MediaTaskGoalDeniedException;
import com.lifeos.media.service.MediaTaskGoalUnavailableException;
import com.lifeos.media.service.MediaTrustLedgerDeniedException;
import com.lifeos.media.service.MediaTrustLedgerUnavailableException;
import com.lifeos.media.service.MediaVersionConflictException;
import com.lifeos.media.signaling.MediaSignalingUnavailableException;
import com.lifeos.media.storage.MediaHlsNotReadyException;
import com.lifeos.media.storage.MediaObjectStorageException;
import com.lifeos.media.storage.MediaUploadDeadlineExceededException;
import com.lifeos.media.storage.MediaUploadTooLargeException;
import com.lifeos.media.storage.UnsupportedMediaContentException;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Stable generic public failures that preserve the owner and provider-implementation boundary. */
@RestControllerAdvice
public class MediaExceptionHandler {

    @ExceptionHandler(MediaAuthenticationFailure.class)
    public ResponseEntity<Map<String, String>> authentication(MediaAuthenticationFailure exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .body(Map.of("error", "Authentication required"));
    }

    @ExceptionHandler(MediaAuthorizationDenied.class)
    public ResponseEntity<Map<String, String>> denied(MediaAuthorizationDenied exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Access denied"));
    }

    @ExceptionHandler(MediaTaskGoalDeniedException.class)
    public ResponseEntity<Map<String, String>> taskGoalDenied(MediaTaskGoalDeniedException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Task action was not authorized"));
    }

    @ExceptionHandler(MediaTrustLedgerDeniedException.class)
    public ResponseEntity<Map<String, String>> trustLedgerDenied(MediaTrustLedgerDeniedException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "Session summary anchor was not authorized"));
    }

    @ExceptionHandler({
        MediaAuthorizationDependencyUnavailable.class,
        MediaAuditUnavailableException.class,
        MediaIdempotencyUnavailableException.class,
        MediaObjectStorageException.class,
        MediaSignalingUnavailableException.class,
        MediaTaskGoalUnavailableException.class,
        MediaTrustLedgerUnavailableException.class
    })
    public ResponseEntity<Map<String, String>> unavailable(RuntimeException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(Map.of("error", "Media dependency temporarily unavailable"));
    }

    @ExceptionHandler(MediaResourceNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(MediaResourceNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Resource not found"));
    }

    @ExceptionHandler(MediaVersionConflictException.class)
    public ResponseEntity<Map<String, String>> versionConflict(MediaVersionConflictException exception) {
        return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED)
                .body(Map.of("error", "Media representation is no longer current"));
    }

    @ExceptionHandler(MediaVersionPreconditionRequiredException.class)
    public ResponseEntity<Map<String, String>> missingVersion(MediaVersionPreconditionRequiredException exception) {
        return ResponseEntity.status(428).body(Map.of("error", "If-Match is required for this mutation"));
    }

    @ExceptionHandler(InvalidMediaVersionPreconditionException.class)
    public ResponseEntity<Map<String, String>> invalidVersion(InvalidMediaVersionPreconditionException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", "A valid strong If-Match header is required"));
    }

    @ExceptionHandler(InvalidMediaIdempotencyKeyException.class)
    public ResponseEntity<Map<String, String>> invalidIdempotency(InvalidMediaIdempotencyKeyException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", "A valid Idempotency-Key header is required"));
    }

    @ExceptionHandler(MediaIdempotencyConflictException.class)
    public ResponseEntity<Map<String, String>> idempotencyConflict(MediaIdempotencyConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "Idempotency key conflicts with an existing request"));
    }

    @ExceptionHandler({MediaLifecycleTransitionException.class, MediaSessionNotJoinableException.class})
    public ResponseEntity<Map<String, String>> lifecycle(RuntimeException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "Media lifecycle transition is not valid"));
    }

    @ExceptionHandler(MediaHlsNotReadyException.class)
    public ResponseEntity<Map<String, String>> hlsNotReady(MediaHlsNotReadyException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "HLS media is not available"));
    }

    @ExceptionHandler(UnsupportedMediaContentException.class)
    public ResponseEntity<Map<String, String>> unsupportedContent(UnsupportedMediaContentException exception) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(Map.of("error", "Only verified MP4 and WebM sources are supported"));
    }

    @ExceptionHandler({MediaUploadTooLargeException.class})
    public ResponseEntity<Map<String, String>> uploadTooLarge(MediaUploadTooLargeException exception) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("error", "Media upload is too large"));
    }

    @ExceptionHandler(MediaUploadDeadlineExceededException.class)
    public ResponseEntity<Map<String, String>> uploadDeadline(MediaUploadDeadlineExceededException exception) {
        return ResponseEntity.status(HttpStatus.REQUEST_TIMEOUT).body(Map.of("error", "Media upload timed out"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalidInput(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid request"));
    }
}
