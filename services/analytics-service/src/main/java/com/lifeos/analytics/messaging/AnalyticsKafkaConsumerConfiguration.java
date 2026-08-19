package com.lifeos.analytics.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/** Bounded poison-record handling for the optional Analytics V2 projection consumer. */
@Configuration
@ConditionalOnProperty(prefix = "analytics", name = "kafka-enabled", havingValue = "true")
public class AnalyticsKafkaConsumerConfiguration {

    @Bean
    DefaultErrorHandler analyticsKafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate, (record, exception) -> AnalyticsKafkaFailurePolicy.deadLetterDestination(record));
        DefaultErrorHandler handler = new DefaultErrorHandler(
                recoverer, new FixedBackOff(AnalyticsKafkaFailurePolicy.RETRY_INTERVAL_MILLIS,
                        AnalyticsKafkaFailurePolicy.MAX_RETRIES));
        handler.addNotRetryableExceptions(IllegalArgumentException.class);
        handler.setCommitRecovered(true);
        return handler;
    }
}
