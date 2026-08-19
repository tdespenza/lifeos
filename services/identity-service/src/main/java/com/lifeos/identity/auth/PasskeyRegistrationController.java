package com.lifeos.identity.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated WebAuthn registration ceremony boundary. */
@RestController
public class PasskeyRegistrationController {

    private static final String FAILURE = "The passkey registration request could not be completed.";
    private static final String TEMPORARY_FAILURE = "Authentication is temporarily unavailable.";

    private final PasskeyRegistrationService registrationService;
    private final JwtValidationService validationService;
    private final ClientAddressResolver clientAddressResolver;

    public PasskeyRegistrationController(
            PasskeyRegistrationService registrationService,
            JwtValidationService validationService,
            ClientAddressResolver clientAddressResolver) {
        this.registrationService = registrationService;
        this.validationService = validationService;
        this.clientAddressResolver = clientAddressResolver;
    }

    @PostMapping(value = "/api/v1/auth/passkey/registration/options", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PasskeyRegistrationOptions> options(HttpServletRequest request) {
        String clientAddress = clientAddressResolver.resolve(request);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(registrationService.begin(authenticate(request), clientAddress));
    }

    @PostMapping(value = "/api/v1/auth/passkey/registration", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> registration(
            @Valid @RequestBody PasskeyRegistrationRequest body,
            HttpServletRequest request) {
        registrationService.complete(
                authenticate(request), body, clientAddressResolver.resolve(request));
        return ResponseEntity.noContent().build();
    }

    @GetMapping(value = "/api/v1/auth/passkey/credentials", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<PasskeyCredentialSummary>> credentials(HttpServletRequest request) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(registrationService.list(authenticate(request)));
    }

    @DeleteMapping("/api/v1/auth/passkey/credentials/{credentialId}")
    public ResponseEntity<Void> revoke(
            @PathVariable UUID credentialId,
            HttpServletRequest request) {
        registrationService.revoke(
                authenticate(request), credentialId, clientAddressResolver.resolve(request));
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(AuthenticationFailureException.class)
    public ResponseEntity<ProblemDetail> authenticationFailure() {
        return problem(HttpStatus.UNAUTHORIZED, FAILURE);
    }

    @ExceptionHandler(AuthenticationDependencyUnavailableException.class)
    public ResponseEntity<ProblemDetail> dependencyFailure() {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, TEMPORARY_FAILURE);
    }

    @ExceptionHandler(PasskeyCredentialRemovalConflictException.class)
    public ResponseEntity<ProblemDetail> removalConflict() {
        return problem(HttpStatus.CONFLICT, "At least one other sign-in method must remain enabled.");
    }

    @ExceptionHandler(PasskeyCredentialNotFoundException.class)
    public ResponseEntity<ProblemDetail> credentialNotFound() {
        return problem(HttpStatus.NOT_FOUND, FAILURE);
    }

    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
        org.springframework.web.bind.MethodArgumentNotValidException.class})
    public ResponseEntity<ProblemDetail> invalidRequest() {
        return problem(HttpStatus.BAD_REQUEST, FAILURE);
    }

    private AuthenticatedSubject authenticate(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new AuthenticationFailureException();
        }
        String token = header.substring(7).trim();
        if (token.isBlank() || token.chars().anyMatch(Character::isWhitespace)) {
            throw new AuthenticationFailureException();
        }
        return validationService.validate(token);
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setTitle("Passkey registration failed");
        return ResponseEntity.status(status).body(body);
    }
}
