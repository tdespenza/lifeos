package com.lifeos.assistant.history;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class ConversationHistoryPropertiesTest {

    @Test
    void disabledHistoryDoesNotRequireAnEncryptionKey() {
        ConversationHistoryProperties properties = new ConversationHistoryProperties();

        assertThat(properties.isEncryptionKeyValid()).isTrue();
    }

    @Test
    void enabledHistoryRequiresExactlyAes256KeyAndBoundedLoopbackUri() {
        ConversationHistoryProperties properties = new ConversationHistoryProperties();
        properties.setEnabled(true);
        properties.setEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        properties.setUri("mongodb://localhost:27017/lifeos_ai_history");
        properties.setConnectTimeout(Duration.ofSeconds(2));

        assertThat(properties.isEncryptionKeyValid()).isTrue();
        assertThat(properties.isUriValid()).isTrue();

        properties.setEncryptionKey(Base64.getEncoder().encodeToString(new byte[16]));
        assertThat(properties.isEncryptionKeyValid()).isFalse();
        properties.setUri("mongodb://database.internal:27017/history");
        assertThat(properties.isUriValid()).isFalse();
    }
}
