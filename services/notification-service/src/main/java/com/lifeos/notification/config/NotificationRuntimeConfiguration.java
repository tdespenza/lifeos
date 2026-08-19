package com.lifeos.notification.config;

import java.time.Clock;
import java.util.random.RandomGenerator;
import com.lifeos.notification.delivery.FullJitterRetryPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/** Injectable runtime primitives keep retry and lifecycle tests deterministic. */
@Configuration
public class NotificationRuntimeConfiguration {

    @Bean
    Clock notificationClock() {
        return Clock.systemUTC();
    }

    @Bean
    RandomGenerator notificationRandomGenerator() {
        return RandomGenerator.getDefault();
    }

    @Bean
    FullJitterRetryPolicy notificationDeliveryRetryPolicy(
            NotificationProperties properties, RandomGenerator notificationRandomGenerator) {
        return new FullJitterRetryPolicy(
                properties.getDelivery().getInitialBackoff(),
                properties.getDelivery().getMaxBackoff(),
                notificationRandomGenerator);
    }

    @Bean
    FullJitterRetryPolicy notificationOutboxRetryPolicy(
            NotificationProperties properties, RandomGenerator notificationRandomGenerator) {
        return new FullJitterRetryPolicy(
                properties.getOutbox().getInitialBackoff(),
                properties.getOutbox().getMaxBackoff(),
                notificationRandomGenerator);
    }

    /**
     * A status event must reach every running service instance so each process can fan it out to
     * only its own SSE sessions. Deriving the group here, rather than accepting an arbitrary
     * shared group-id property, prevents replicas from silently load-balancing those events.
     */
    @Bean("notificationRealtimeConsumerGroup")
    @ConditionalOnProperty(
            value = "notification.kafka.realtime-consumer-enabled",
            havingValue = "true",
            matchIfMissing = true)
    String notificationRealtimeConsumerGroup(NotificationProperties properties) {
        String instanceId = properties.getKafka().getRealtimeInstanceId();
        if (!StringUtils.hasText(instanceId) || !instanceId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,119}")) {
            throw new IllegalStateException(
                    "notification.kafka.realtime-instance-id must be a unique 1-120 character "
                            + "deployment identity ([A-Za-z0-9._-]); set NOTIFICATION_INSTANCE_ID or HOSTNAME");
        }
        return "notification-realtime-" + instanceId;
    }
}
