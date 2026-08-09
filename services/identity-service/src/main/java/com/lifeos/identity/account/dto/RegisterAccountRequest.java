package com.lifeos.identity.account.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Validated input for account registration.
 *
 * @param email non-blank, syntactically valid email address
 * @param displayName non-blank name shown for the account
 */
public record RegisterAccountRequest(
        @NotBlank @Email String email,
        @NotBlank String displayName) {
}
