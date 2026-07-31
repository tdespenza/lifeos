package com.lifeos.identity.account;

import com.lifeos.identity.account.dto.AccountResponse;
import com.lifeos.identity.account.dto.RegisterAccountRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserAccountController {

    private final UserAccountService service;

    public UserAccountController(UserAccountService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/accounts")
    public ResponseEntity<AccountResponse> register(@Valid @RequestBody RegisterAccountRequest request) {
        UserAccount account = service.register(request.email(), request.displayName());
        AccountResponse body = AccountResponse.from(account);
        return ResponseEntity.created(URI.create("/api/v1/accounts/" + body.id())).body(body);
    }

    @GetMapping("/api/v1/accounts/{id}")
    public AccountResponse getById(@PathVariable UUID id) {
        return AccountResponse.from(service.getById(id));
    }

    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleDuplicateEmail(EmailAlreadyRegisteredException ex) {
        return ex.getMessage();
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException ex) {
        return ex.getMessage();
    }
}
