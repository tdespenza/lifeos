package com.lifeos.identity.account;

import com.lifeos.identity.account.dto.AccountResponse;
import com.lifeos.identity.account.dto.RegisterAccountRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for account registration and account lookup.
 */
@RestController
public class UserAccountController {

    private final UserAccountService service;

    /**
     * Creates the controller with its account application service.
     *
     * @param service account application service
     */
    public UserAccountController(UserAccountService service) {
        this.service = service;
    }

    /**
     * Registers an account after validating its email and display name.
     *
     * @param request validated account-registration data
     * @return a {@code 201 Created} response containing the account and its resource location
     */
    @PostMapping("/api/v1/accounts")
    public ResponseEntity<AccountResponse> register(@Valid @RequestBody RegisterAccountRequest request) {
        UserAccount account = service.register(request.email(), request.displayName());
        AccountResponse body = AccountResponse.from(account);
        return ResponseEntity.created(URI.create("/api/v1/accounts/" + body.id())).body(body);
    }

    /**
     * Retrieves an account by its stable identifier.
     *
     * @param id account UUID from the resource path
     * @return the account representation
     * @throws NoSuchElementException when no account exists for {@code id}
     */
    @GetMapping("/api/v1/accounts/{id}")
    public AccountResponse getById(@PathVariable UUID id) {
        return AccountResponse.from(service.getById(id));
    }

    /**
     * Converts a duplicate email failure into a sanitized conflict response.
     *
     * @param ex duplicate-registration exception
     * @return a client-safe conflict message
     */
    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleDuplicateEmail(EmailAlreadyRegisteredException ex) {
        return ex.getMessage();
    }

    /**
     * Converts bean-validation failures into a generic problem detail without echoing field values.
     *
     * @return a client-safe bad-request problem detail
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidationFailure() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "The request contains invalid or missing fields.");
        problem.setTitle("Invalid account registration request");
        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * Converts a missing account lookup into a not-found response.
     *
     * @param ex missing-account exception
     * @return a not-found message
     */
    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException ex) {
        return ex.getMessage();
    }
}
