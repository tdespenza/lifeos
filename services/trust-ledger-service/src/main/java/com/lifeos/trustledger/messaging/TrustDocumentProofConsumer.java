package com.lifeos.trustledger.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** At-least-once Document Vault proof consumer with durable request-id deduplication. */
@Component
@ConditionalOnProperty(prefix = "trust-ledger.kafka", name = "enabled", havingValue = "true")
public class TrustDocumentProofConsumer {

    private final TrustDocumentProofIngressService ingress;

    public TrustDocumentProofConsumer(TrustDocumentProofIngressService ingress) {
        this.ingress = ingress;
    }

    @KafkaListener(
            topics = "${trust-ledger.kafka.topic}",
            groupId = "${trust-ledger.kafka.group}",
            containerFactory = "trustLedgerKafkaListenerContainerFactory")
    public void consume(String payload) {
        ingress.accept(payload);
    }
}
