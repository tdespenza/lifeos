package com.lifeos.notification.messaging;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Bounded poison-record handling for notification commands. Unrecoverable contract/ID-conflict
 * records are published to {@code <source-topic>.DLT}; transient failures retry twice at one-second
 * intervals before that same durable Kafka dead-letter route. Delivery dead letters remain in the
 * local database because they represent provider work, not malformed producer commands.
 */
@Configuration
public class NotificationKafkaConsumerConfiguration {

    @Bean
    DefaultErrorHandler notificationKafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        NotificationKafkaFailurePolicy policy = new NotificationKafkaFailurePolicy();
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate, (record, exception) -> policy.deadLetterDestination(record));
        DefaultErrorHandler handler = new DefaultErrorHandler(
                recoverer, new FixedBackOff(NotificationKafkaFailurePolicy.RETRY_INTERVAL_MILLIS, NotificationKafkaFailurePolicy.MAX_RETRIES));
        handler.addNotRetryableExceptions(InvalidNotificationEventException.class, NotificationEventIdConflictException.class);
        handler.setCommitRecovered(true);
        return handler;
    }
}
