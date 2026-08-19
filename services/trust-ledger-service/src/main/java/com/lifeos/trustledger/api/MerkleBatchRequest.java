package com.lifeos.trustledger.api;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Ordered, unique document digests for one bounded Merkle batch. */
public record MerkleBatchRequest(
        @NotEmpty(message = "documentDigests must not be empty")
        @Size(max = 10_000, message = "documentDigests must be bounded")
        List<@NotNull @Pattern(regexp = "[0-9A-Fa-f]{64}", message = "each digest must be SHA-256 hex") String> documentDigests) {
}
