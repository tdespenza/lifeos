package com.lifeos.documentvault.proof;

public class DocumentProofSerializationException extends RuntimeException {
    public DocumentProofSerializationException(Throwable cause) {
        super("document proof event serialization failed", cause);
    }
}
