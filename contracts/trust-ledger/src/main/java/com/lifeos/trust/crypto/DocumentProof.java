package com.lifeos.trust.crypto;

import java.util.Objects;

/** A non-content-bearing canonical document digest result. */
public record DocumentProof(String algorithm, Hash32 digest, long contentBytes) {

    public DocumentProof {
        if (!DocumentHasher.ALGORITHM.equals(algorithm)) {
            throw new IllegalArgumentException("unsupported document proof algorithm");
        }
        Objects.requireNonNull(digest, "digest must not be null");
        if (contentBytes < 1) {
            throw new IllegalArgumentException("document proof content size must be positive");
        }
    }
}
