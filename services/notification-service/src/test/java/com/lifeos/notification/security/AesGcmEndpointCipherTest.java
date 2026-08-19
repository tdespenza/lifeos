package com.lifeos.notification.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lifeos.notification.config.NotificationProperties;
import org.junit.jupiter.api.Test;

class AesGcmEndpointCipherTest {

    @Test
    void encryptsWithDistinctIvsAndRejectsTampering() {
        AesGcmEndpointCipher cipher = new AesGcmEndpointCipher(properties());

        String first = cipher.encrypt("person@example.test");
        String second = cipher.encrypt("person@example.test");

        assertNotEquals(first, second);
        assertEquals("person@example.test", cipher.decrypt(first));
        assertThrows(EndpointCipherException.class, () -> cipher.decrypt(first.substring(0, first.length() - 1) + "x"));
    }

    private static NotificationProperties properties() {
        NotificationProperties properties = new NotificationProperties();
        properties.setEndpointEncryptionKey("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        properties.setIdempotencySecret("notification-test-idempotency-secret-with-32-bytes");
        return properties;
    }
}
