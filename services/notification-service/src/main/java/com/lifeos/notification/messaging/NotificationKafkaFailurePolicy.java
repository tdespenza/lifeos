package com.lifeos.notification.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;

/** Pure bounded retry/DLT policy kept independently testable from a running Kafka broker. */
public final class NotificationKafkaFailurePolicy {

    public static final long RETRY_INTERVAL_MILLIS = 1_000L;
    public static final long MAX_RETRIES = 2L;

    public boolean retryable(Throwable failure) {
        return !(failure instanceof InvalidNotificationEventException)
                && !(failure instanceof NotificationEventIdConflictException);
    }

    public TopicPartition deadLetterDestination(ConsumerRecord<?, ?> record) {
        return new TopicPartition(record.topic() + ".DLT", record.partition());
    }
}
