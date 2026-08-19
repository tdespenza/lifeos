package com.lifeos.identity.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class IdentityRecoveryNotificationPropertiesTest {

    @Test
    void retryTimingIsBoundedToKeepJitterArithmeticSafe() {
        IdentityRecoveryNotificationProperties properties = new IdentityRecoveryNotificationProperties();

        assertThat(properties.isTimingValid()).isTrue();

        properties.setMaxBackoff(Duration.ofDays(1).plusMillis(1));
        assertThat(properties.isTimingValid()).isFalse();

        properties.setMaxBackoff(Duration.ofMinutes(5));
        properties.setInitialBackoff(Duration.ofHours(1).plusMillis(1));
        assertThat(properties.isTimingValid()).isFalse();
    }
}
