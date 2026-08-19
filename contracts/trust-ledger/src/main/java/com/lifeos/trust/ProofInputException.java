package com.lifeos.trust;

/** Controlled validation failure for bounded document-proof and Merkle-tree input. */
public class ProofInputException extends IllegalArgumentException {

    public ProofInputException(String message) {
        super(message);
    }
}
