package com.lifeos.profile.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class JournalPropertiesTest {

    @Test
    void disabledStorageDoesNotRequireAnEncryptionKey() {
        JournalProperties properties = new JournalProperties();
        properties.setEncryptionKey("");

        assertThat(properties.isEncryptionKeyValid()).isTrue();
        assertThat(properties.isUriValid()).isTrue();
    }

    @Test
    void enabledStorageRequiresExactlyAes256KeyAndPrivateOrSrvUri() {
        JournalProperties properties = new JournalProperties();
        properties.setEnabled(true);
        properties.setEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));

        assertThat(properties.isEncryptionKeyValid()).isTrue();
        assertThat(properties.isUriValid()).isTrue();

        properties.setEncryptionKey(Base64.getEncoder().encodeToString(new byte[16]));
        assertThat(properties.isEncryptionKeyValid()).isFalse();
        properties.setUri("mongodb://db.internal:27017");
        assertThat(properties.isUriValid()).isFalse();
    }

    @Test
    void timeoutAndBoundsAreExplicitlyChecked() {
        JournalProperties properties = new JournalProperties();
        properties.setTimeout(Duration.ofSeconds(31));
        assertThat(properties.isTimeoutValid()).isFalse();
        properties.setTimeout(Duration.ofSeconds(2));
        assertThat(properties.isTimeoutValid()).isTrue();
    }
}
