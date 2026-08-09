package com.lifeos.identity.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Validated first-party login input.
 *
 * @param email account email address
 * @param password raw password supplied only for the duration of authentication
 */
public record LoginRequest(
        @NotBlank @Email String email,
        @NotBlank String password) {

    /**
     * Trims the email before Bean Validation so formatting whitespace does not cause a false
     * {@code @Email} rejection. Null remains null for {@code @NotBlank} to reject.
     */
    public LoginRequest {
        email = email == null ? null : email.strip();
    }
}
