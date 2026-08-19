package com.lifeos.trustledger.api;

import com.lifeos.trust.ProofInputException;
import com.lifeos.trustledger.access.TrustAuthenticationFailure;
import com.lifeos.trustledger.access.TrustAuthorizationDenied;
import com.lifeos.trustledger.access.TrustAuthorizationDependencyUnavailable;
import com.lifeos.trustledger.anchor.TrustAnchorIdempotencyConflictException;
import com.lifeos.trustledger.anchor.TrustAnchorUnavailableException;
import com.lifeos.trustledger.certificate.TaskGoalCertificateUnavailableException;
import jakarta.validation.ConstraintViolationException;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** Maps proof and identity failures to stable public errors without leaking document content. */
@RestControllerAdvice
public class TrustLedgerExceptionHandler {

    @ExceptionHandler(TrustAuthenticationFailure.class)
    public ResponseEntity<ProblemDetail> authenticationRequired() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .body(problem(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "A valid bearer credential is required"));
    }

    @ExceptionHandler(TrustAuthorizationDenied.class)
    public ResponseEntity<ProblemDetail> authorizationDenied() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(problem(HttpStatus.FORBIDDEN, "AUTHORIZATION_DENIED", "Proof operation is not permitted"));
    }

    @ExceptionHandler(TrustAuthorizationDependencyUnavailable.class)
    public ResponseEntity<ProblemDetail> authorizationUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(problem(HttpStatus.SERVICE_UNAVAILABLE, "AUTHORIZATION_UNAVAILABLE", "Retry shortly"));
    }

    @ExceptionHandler(TrustAnchorIdempotencyConflictException.class)
    public ResponseEntity<ProblemDetail> anchorIdempotencyConflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(problem(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_CONFLICT", "The anchor key conflicts with the request"));
    }

    @ExceptionHandler(TrustAnchorUnavailableException.class)
    public ResponseEntity<ProblemDetail> anchorUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(problem(HttpStatus.SERVICE_UNAVAILABLE, "ANCHOR_UNAVAILABLE", "External anchoring is unavailable"));
    }

    @ExceptionHandler(TaskGoalCertificateUnavailableException.class)
    public ResponseEntity<ProblemDetail> goalCertificateUnavailable() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(problem(HttpStatus.NOT_FOUND, "GOAL_CERTIFICATE_UNAVAILABLE", "The completed goal is unavailable"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ProblemDetail> uploadTooLarge() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(problem(HttpStatus.PAYLOAD_TOO_LARGE, "DOCUMENT_TOO_LARGE", "Document exceeds the configured limit"));
    }

    @ExceptionHandler({ProofInputException.class, IllegalArgumentException.class, ConstraintViolationException.class})
    public ResponseEntity<ProblemDetail> invalidProofInput() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(problem(HttpStatus.BAD_REQUEST, "INVALID_PROOF_INPUT", "Proof input is invalid or exceeds a bound"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> invalidRequestBody() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(problem(HttpStatus.BAD_REQUEST, "INVALID_PROOF_INPUT", "Proof input is invalid or exceeds a bound"));
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<ProblemDetail> unreadableDocument() {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(problem(HttpStatus.UNPROCESSABLE_ENTITY, "DOCUMENT_UNREADABLE", "Document content could not be read"));
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setProperty("code", code);
        return body;
    }
}
