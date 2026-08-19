package com.lifeos.identity.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdentityRecoveryNotificationFactoryTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void buildsPrivacySafeVersionedCloudEventWithoutRawRecoveryCode() throws Exception {
        IdentityRecoveryNotificationProperties properties = new IdentityRecoveryNotificationProperties();
        properties.setTopic("lifeos.notification.requested.v2");
        ObjectMapper objectMapper = mapper();
        IdentityRecoveryNotificationFactory factory = new IdentityRecoveryNotificationFactory(
                objectMapper, properties, Clock.fixed(NOW, ZoneOffset.UTC));
        UUID accountId = UUID.randomUUID();

        IdentityNotificationOutboxEvent outbox = factory.recoveryCodesIssued(accountId);
        JsonNode cloudEvent = objectMapper.readTree(outbox.getPayloadJson());

        assertThat(outbox.getTopic()).isEqualTo("lifeos.notification.requested.v2");
        assertThat(outbox.getPartitionKey()).isEqualTo(accountId.toString());
        assertThat(outbox.getEventType()).isEqualTo("com.lifeos.notification.requested.v2");
        assertThat(cloudEvent.path("type").asText()).isEqualTo("com.lifeos.notification.requested.v2");
        assertThat(cloudEvent.path("data").path("recipientAccountId").asText())
                .isEqualTo(accountId.toString());
        assertThat(cloudEvent.path("data").path("tenantId").asText())
                .isEqualTo("personal:" + accountId);
        assertThat(cloudEvent.path("data").path("category").asText())
                .isEqualTo("security.passkey.recovery-codes-issued");
        assertThat(outbox.getPayloadJson()).doesNotContain("ABCD-EFGH-JKLM", "test-passkey-recovery-secret");
    }

    @Test
    void createsDistinctGenericNotificationForSuccessfulRecovery() throws Exception {
        IdentityRecoveryNotificationProperties properties = new IdentityRecoveryNotificationProperties();
        ObjectMapper objectMapper = mapper();
        IdentityRecoveryNotificationFactory factory = new IdentityRecoveryNotificationFactory(
                objectMapper, properties, Clock.fixed(NOW, ZoneOffset.UTC));
        UUID accountId = UUID.randomUUID();

        IdentityNotificationOutboxEvent outbox = factory.recoverySucceeded(accountId);

        assertThat(outbox.getId()).isNotNull();
        assertThat(outbox.getPayloadJson()).contains("security.passkey.recovery-succeeded");
        assertThat(outbox.getPayloadJson()).contains("used to sign in");
    }

    private static ObjectMapper mapper() {
        return JsonMapper.builder().addModule(new JavaTimeModule()).build();
    }
}
