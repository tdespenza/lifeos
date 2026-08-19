package com.lifeos.trustledger.certificate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import java.util.UUID;

public record TrustGoalCertificateVerificationRequest(
        @NotNull UUID goalId,
        @PositiveOrZero long goalVersion,
        @NotNull Instant completedAt,
        @NotBlank String achievementDigestSha256) {
}
