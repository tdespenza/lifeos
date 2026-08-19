package com.lifeos.assistant.audit;

import com.lifeos.assistant.config.AiAuditOutboxProperties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Explicit opt-in beans for the AI audit hash relay. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "ai-assistant.audit-outbox", name = "relay-enabled", havingValue = "true")
public class AiAuditHashOutboxConfiguration {

    @Bean(destroyMethod = "shutdown")
    ExecutorService aiAuditOutboxExecutor(AiAuditOutboxProperties properties) {
        return Executors.newFixedThreadPool(properties.getMaxConcurrentPublishes());
    }

    @Bean
    AiAuditHashOutboxPublisher aiAuditHashOutboxPublisher(
            KafkaTemplate<String, String> kafkaTemplate, AiAuditOutboxProperties properties) {
        return new KafkaAiAuditHashOutboxPublisher(kafkaTemplate, properties);
    }
}
