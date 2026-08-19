package com.lifeos.notification.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

class NotificationKafkaFailurePolicyTest {

    @Test
    void sendsPoisonRecordsToTheSourceTopicDltWithoutHotLooping() {
        NotificationKafkaFailurePolicy policy = new NotificationKafkaFailurePolicy();
        ConsumerRecord<String, String> record = new ConsumerRecord<>("lifeos.notification.requested.v1", 4, 12L, "user", "bad");

        assertEquals(new TopicPartition("lifeos.notification.requested.v1.DLT", 4), policy.deadLetterDestination(record));
        assertFalse(policy.retryable(new InvalidNotificationEventException("invalid contract")));
        assertFalse(policy.retryable(new NotificationEventIdConflictException()));
    }

    @Test
    void keepsTransientFailuresBoundedToTwoRetriesBeforeTheSameDltRoute() {
        NotificationKafkaFailurePolicy policy = new NotificationKafkaFailurePolicy();

        assertTrue(policy.retryable(new IllegalStateException("dependency unavailable")));
        assertEquals(2L, NotificationKafkaFailurePolicy.MAX_RETRIES);
        assertEquals(1_000L, NotificationKafkaFailurePolicy.RETRY_INTERVAL_MILLIS);
    }
}
