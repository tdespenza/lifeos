package com.lifeos.analytics.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

/** Ensures malformed Analytics events have a deterministic durable DLT destination. */
class AnalyticsKafkaFailurePolicyTest {

    @Test
    void routesTheSourcePartitionToItsDeadLetterTopic() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("lifeos.analytics.v2", 2, 4L, "key", "payload");

        assertThat(AnalyticsKafkaFailurePolicy.deadLetterDestination(record))
                .isEqualTo(new TopicPartition("lifeos.analytics.v2.DLT", 2));
        assertThat(AnalyticsKafkaFailurePolicy.RETRY_INTERVAL_MILLIS).isEqualTo(1_000L);
        assertThat(AnalyticsKafkaFailurePolicy.MAX_RETRIES).isEqualTo(2L);
    }
}
