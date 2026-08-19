package com.lifeos.identity.account.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Validated input for account registration.
 *
 * @param email non-blank, syntactically valid email address
 * @param displayName non-blank name shown for the account
 * @param password first-party password, retained only for the registration command
 */
public record RegisterAccountRequest(
        @NotBlank @Email String email,
        @NotBlank String displayName,
        @NotBlank String password) {

    /**
     * Trims email before Bean Validation, matching the first-party login boundary. The password is
     * deliberately not normalized: leading and trailing whitespace are valid password characters.
     */
    public RegisterAccountRequest {
        email = email == null ? null : email.strip();
    }

    /** Prevents request logging from disclosing account or credential material. */
    @Override
    public String toString() {
        return "RegisterAccountRequest[redacted]";
    }
}
