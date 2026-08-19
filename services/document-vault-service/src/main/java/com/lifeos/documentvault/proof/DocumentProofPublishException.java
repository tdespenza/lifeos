package com.lifeos.documentvault.proof;

public class DocumentProofPublishException extends RuntimeException {
    public DocumentProofPublishException(Throwable cause) {
        super("document proof event publish failed", cause);
    }
}
