package com.lifeos.trustledger.anchor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;

public record TrustDocumentProofVerificationRequest(
        @NotNull UUID documentId,
        @PositiveOrZero long documentVersion,
        @NotBlank String checksumSha256) {
}
