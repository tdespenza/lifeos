package com.lifeos.trustledger.api;

import com.lifeos.trust.merkle.MerkleSiblingSide;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** One client-supplied bounded proof step used only for stateless verification. */
public record MerkleProofStepRequest(
        @Pattern(regexp = "[0-9A-Fa-f]{64}", message = "siblingDigest must be SHA-256 hex") String siblingDigest,
        @NotNull(message = "siblingSide must be present") MerkleSiblingSide siblingSide) {
}
