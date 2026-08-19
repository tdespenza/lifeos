package com.lifeos.analytics.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;

/** Pure topic-routing policy for Analytics poison records. */
public final class AnalyticsKafkaFailurePolicy {

    public static final long RETRY_INTERVAL_MILLIS = 1_000L;
    public static final long MAX_RETRIES = 2L;

    private AnalyticsKafkaFailurePolicy() {}

    public static TopicPartition deadLetterDestination(ConsumerRecord<?, ?> record) {
        return new TopicPartition(record.topic() + ".DLT", record.partition());
    }
}
