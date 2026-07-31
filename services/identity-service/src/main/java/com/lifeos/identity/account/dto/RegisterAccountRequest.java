package com.lifeos.identity.account.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record RegisterAccountRequest(
        @NotBlank @Email String email,
        @NotBlank String displayName) {
}
