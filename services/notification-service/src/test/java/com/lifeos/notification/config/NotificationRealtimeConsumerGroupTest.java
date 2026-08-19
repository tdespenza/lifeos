package com.lifeos.notification.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class NotificationRealtimeConsumerGroupTest {

    private final NotificationRuntimeConfiguration configuration = new NotificationRuntimeConfiguration();

    @Test
    void derivesAReplicaSpecificGroupInsteadOfAcceptingASharedDefault() {
        NotificationProperties first = properties("notification-pod-a");
        NotificationProperties second = properties("notification-pod-b");

        String firstGroup = configuration.notificationRealtimeConsumerGroup(first);
        String secondGroup = configuration.notificationRealtimeConsumerGroup(second);

        assertEquals("notification-realtime-notification-pod-a", firstGroup);
        assertNotEquals(firstGroup, secondGroup);
    }

    @Test
    void rejectsMissingOrUnsafeReplicaIdentityWhenRealtimeFanoutIsEnabled() {
        assertThrows(
                IllegalStateException.class,
                () -> configuration.notificationRealtimeConsumerGroup(new NotificationProperties()));
        assertThrows(
                IllegalStateException.class,
                () -> configuration.notificationRealtimeConsumerGroup(properties("shared group")));
    }

    private static NotificationProperties properties(String instanceId) {
        NotificationProperties properties = new NotificationProperties();
        properties.getKafka().setRealtimeInstanceId(instanceId);
        return properties;
    }
}
