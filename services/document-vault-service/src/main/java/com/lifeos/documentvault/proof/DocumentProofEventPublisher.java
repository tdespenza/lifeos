package com.lifeos.documentvault.proof;

public interface DocumentProofEventPublisher {
    void publish(ClaimedDocumentProofOutboxEvent event);
}
