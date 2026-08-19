package com.lifeos.trustledger.messaging;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Bounded, opt-in Kafka consumer settings for Document Vault proof commands. */
@ConfigurationProperties(prefix = "trust-ledger.kafka")
@Validated
public class TrustLedgerKafkaProperties {

    private boolean enabled;

    @NotBlank
    @Size(max = 200)
    private String topic = com.lifeos.events.v1.EventContract.DOCUMENT_PROOF_REQUESTED_V1_TOPIC;

    @NotBlank
    @Size(max = 200)
    private String group = "trust-ledger-document-proof-v1";

    private boolean aiAuditEnabled;

    @NotBlank
    @Size(max = 200)
    private String aiAuditTopic = com.lifeos.events.v1.EventContract.AI_AUDIT_HASH_REQUESTED_V1_TOPIC;

    @NotBlank
    @Size(max = 200)
    private String aiAuditGroup = "trust-ledger-ai-audit-v1";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public boolean isAiAuditEnabled() {
        return aiAuditEnabled;
    }

    public void setAiAuditEnabled(boolean aiAuditEnabled) {
        this.aiAuditEnabled = aiAuditEnabled;
    }

    public String getAiAuditTopic() {
        return aiAuditTopic;
    }

    public void setAiAuditTopic(String aiAuditTopic) {
        this.aiAuditTopic = aiAuditTopic;
    }

    public String getAiAuditGroup() {
        return aiAuditGroup;
    }

    public void setAiAuditGroup(String aiAuditGroup) {
        this.aiAuditGroup = aiAuditGroup;
    }

    @AssertTrue(message = "trust ledger Kafka topic and group must be safe tokens")
    public boolean areTokensValid() {
        return token(topic) && token(group) && token(aiAuditTopic) && token(aiAuditGroup);
    }

    private static boolean token(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,199}");
    }
}
