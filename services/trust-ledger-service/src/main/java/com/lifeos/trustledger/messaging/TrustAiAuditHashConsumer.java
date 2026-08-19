package com.lifeos.trustledger.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Optional at-least-once AI audit commitment consumer with durable event-id deduplication. */
@Component
@ConditionalOnProperty(prefix = "trust-ledger.kafka", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "trust-ledger.kafka", name = "ai-audit-enabled", havingValue = "true")
public class TrustAiAuditHashConsumer {

    private final TrustAiAuditHashIngressService ingress;

    public TrustAiAuditHashConsumer(TrustAiAuditHashIngressService ingress) {
        this.ingress = ingress;
    }

    @KafkaListener(
            topics = "${trust-ledger.kafka.ai-audit-topic}",
            groupId = "${trust-ledger.kafka.ai-audit-group}",
            containerFactory = "trustLedgerKafkaListenerContainerFactory")
    public void consume(String payload) {
        ingress.accept(payload);
    }
}
