package com.lifeos.trustledger.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** A bounded public Merkle verification request; it contains no document content or identity data. */
public record VerifyMerkleProofRequest(
        @Pattern(regexp = "[0-9A-Fa-f]{64}", message = "root must be SHA-256 hex") String root,
        @Min(value = 0, message = "leafIndex must not be negative") int leafIndex,
        @Pattern(regexp = "[0-9A-Fa-f]{64}", message = "documentDigest must be SHA-256 hex") String documentDigest,
        @NotNull(message = "steps must be present")
        @Size(max = 32, message = "steps must be bounded")
        List<@NotNull MerkleProofStepRequest> steps) {
}
