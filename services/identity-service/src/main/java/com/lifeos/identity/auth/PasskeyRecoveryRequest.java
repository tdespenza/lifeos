package com.lifeos.identity.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Unauthenticated recovery request; the generic response prevents account enumeration. */
public record PasskeyRecoveryRequest(
        @NotBlank @Email String email,
        @NotBlank @Pattern(regexp = "[A-Z2-7]{4}(?:-[A-Z2-7]{4}){2}") String code) {
}
