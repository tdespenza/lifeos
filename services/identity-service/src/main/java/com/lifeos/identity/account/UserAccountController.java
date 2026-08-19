package com.lifeos.identity.account;

import com.lifeos.identity.account.dto.AccountResponse;
import com.lifeos.identity.account.dto.RegisterAccountRequest;
import com.lifeos.identity.auth.AuthenticatedSubject;
import com.lifeos.identity.auth.AuthenticationDependencyUnavailableException;
import com.lifeos.identity.auth.AuthenticationFailureException;
import com.lifeos.identity.auth.ClientAddressResolver;
import com.lifeos.identity.auth.JwtValidationService;
import com.lifeos.identity.auth.SecurityAuditEventType;
import com.lifeos.identity.auth.SecurityAuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** REST endpoints for public password enrollment and self-owned account lookup. */
@RestController
public class UserAccountController {

    private static final String REGISTRATION_INVALID_DETAIL = "The registration request is invalid.";
    private static final String REGISTRATION_CONFLICT_DETAIL = "The registration could not be completed.";
    private static final String ACCOUNT_NOT_AVAILABLE_DETAIL = "The requested account is not available.";
    private static final String TEMPORARY_FAILURE_DETAIL = "Identity service is temporarily unavailable.";
    private static final URI ACCOUNT_NOT_AVAILABLE_INSTANCE = URI.create("urn:lifeos:account:not-available");

    private final UserAccountService service;
    private final JwtValidationService validationService;
    private final ClientAddressResolver clientAddressResolver;
    private final SecurityAuditService auditService;

    /**
     * Creates the controller with the public registration and bearer-ownership dependencies.
     *
     * @param service account application service
     * @param validationService bearer/JWT plus durable-session validator
     * @param clientAddressResolver trusted address resolver for redacted audit fingerprints
     * @param auditService redacted audit writer
     */
    public UserAccountController(
            UserAccountService service,
            JwtValidationService validationService,
            ClientAddressResolver clientAddressResolver,
            SecurityAuditService auditService) {
        this.service = service;
        this.validationService = validationService;
        this.clientAddressResolver = clientAddressResolver;
        this.auditService = auditService;
    }

    /**
     * Atomically enrolls a first-party account and its Argon2id password credential.
     *
     * <p>The request must carry exactly one bounded {@code Idempotency-Key}. A matching completed
     * retry returns {@code 200 OK}; the first successful command returns {@code 201 Created}.
     *
     * @param request validated account-registration data
     * @param idempotencyKeyValues all received retry-key header values
     * @param servletRequest request used only for a redacted audit fingerprint
     * @return created or replayed account representation and its resource location
     */
    @PostMapping("/api/v1/accounts")
    public ResponseEntity<AccountResponse> register(
            @Valid @RequestBody RegisterAccountRequest request,
            @RequestHeader(value = AccountRegistrationIdempotencyKey.HEADER_NAME, required = false)
                    List<String> idempotencyKeyValues,
            HttpServletRequest servletRequest) {
        AccountRegistrationResult result = service.register(
                request.email(),
                request.displayName(),
                request.password(),
                idempotencyKeyValues,
                clientAddressResolver.resolve(servletRequest));
        AccountResponse body = AccountResponse.from(result.account());
        URI location = URI.create("/api/v1/accounts/" + body.id());
        ResponseEntity.BodyBuilder response = result.replayed()
                ? ResponseEntity.ok().location(location)
                : ResponseEntity.created(location);
        return response.header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }

    /**
     * Returns only the account represented by the caller's validated bearer subject.
     *
     * <p>A nonexistent target and another account's target intentionally produce the same generic
     * {@code 404} response. The denial audit associates only the caller, never the requested
     * target identifier.
     *
     * @param id account UUID from the resource path
     * @param servletRequest bearer request
     * @return self-owned account representation
     */
    @GetMapping("/api/v1/accounts/{id}")
    public ResponseEntity<AccountResponse> getById(@PathVariable UUID id, HttpServletRequest servletRequest) {
        AuthenticatedSubject subject = authenticate(servletRequest);
        if (!subject.accountId().equals(id)) {
            recordReadDenial(subject, servletRequest);
            throw new AccountNotFoundException();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(AccountResponse.from(service.getById(id)));
    }

    /** Converts public-registration validation errors into a redacted 400 response. */
    @ExceptionHandler({
        InvalidRegistrationPasswordException.class,
        InvalidAccountRegistrationIdempotencyKeyException.class
    })
    public ResponseEntity<ProblemDetail> handleInvalidRegistration() {
        return problem(HttpStatus.BAD_REQUEST, REGISTRATION_INVALID_DETAIL);
    }

    /** Audits malformed JSON and bean-validation rejections that cannot reach the service layer. */
    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        org.springframework.http.converter.HttpMessageNotReadableException.class
    })
    public ResponseEntity<ProblemDetail> handleMalformedRegistration(HttpServletRequest request) {
        try {
            auditService.record(
                    SecurityAuditEventType.ACCOUNT_REGISTRATION_REJECTED,
                    null,
                    clientAddressResolver.resolve(request));
        } catch (RuntimeException exception) {
            return handleTemporaryFailure();
        }
        return problem(HttpStatus.BAD_REQUEST, REGISTRATION_INVALID_DETAIL);
    }

    /** Converts email and retry-key conflicts into one non-enumerating response. */
    @ExceptionHandler({
        EmailAlreadyRegisteredException.class,
        AccountRegistrationIdempotencyConflictException.class
    })
    public ResponseEntity<ProblemDetail> handleRegistrationConflict() {
        return problem(HttpStatus.CONFLICT, REGISTRATION_CONFLICT_DETAIL);
    }

    /** Converts an unavailable reservation, password worker, audit store, or lock into retryable 503. */
    @ExceptionHandler({
        AccountRegistrationIdempotencyUnavailableException.class,
        AuthenticationDependencyUnavailableException.class
    })
    public ResponseEntity<ProblemDetail> handleTemporaryFailure() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(problemDetail(HttpStatus.SERVICE_UNAVAILABLE, TEMPORARY_FAILURE_DETAIL));
    }

    /** Returns the same generic not-found body for missing and unauthorized account targets. */
    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound() {
        ProblemDetail problem = problemDetail(HttpStatus.NOT_FOUND, ACCOUNT_NOT_AVAILABLE_DETAIL);
        // Spring otherwise derives instance from the requested URI, which would make the two
        // deliberately indistinguishable account-not-found paths serialize differently.
        problem.setInstance(ACCOUNT_NOT_AVAILABLE_INSTANCE);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    private AuthenticatedSubject authenticate(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new AuthenticationFailureException();
        }
        String token = authorization.substring(7).trim();
        if (token.isBlank()) {
            throw new AuthenticationFailureException();
        }
        return validationService.validate(token);
    }

    private void recordReadDenial(AuthenticatedSubject subject, HttpServletRequest request) {
        try {
            auditService.record(
                    SecurityAuditEventType.ACCOUNT_READ_DENIED,
                    subject.accountId(),
                    clientAddressResolver.resolve(request));
        } catch (RuntimeException exception) {
            throw new AuthenticationDependencyUnavailableException(exception);
        }
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(problemDetail(status, detail));
    }

    private ProblemDetail problemDetail(HttpStatus status, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle("Account request failed");
        return problem;
    }
}
