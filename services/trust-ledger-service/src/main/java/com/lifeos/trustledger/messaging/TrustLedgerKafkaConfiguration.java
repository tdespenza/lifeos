package com.lifeos.trustledger.messaging;

import java.time.Duration;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.util.backoff.FixedBackOff;

/** Fail-closed, bounded retry policy for malformed or unavailable proof commands. */
@Configuration
@ConditionalOnProperty(prefix = "trust-ledger.kafka", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(TrustLedgerKafkaProperties.class)
public class TrustLedgerKafkaConfiguration {

    @Bean
    DefaultErrorHandler trustLedgerKafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, ignored) -> new TopicPartition(record.topic() + ".DLT", record.partition()));
        return new DefaultErrorHandler(
                recoverer, new FixedBackOff(Duration.ofSeconds(1).toMillis(), 2));
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> trustLedgerKafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            DefaultErrorHandler trustLedgerKafkaErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(trustLedgerKafkaErrorHandler);
        factory.getContainerProperties().setAckMode(
                org.springframework.kafka.listener.ContainerProperties.AckMode.RECORD);
        return factory;
    }
}
