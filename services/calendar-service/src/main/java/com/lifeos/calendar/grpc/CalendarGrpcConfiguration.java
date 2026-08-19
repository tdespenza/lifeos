package com.lifeos.calendar.grpc;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "grpc.server.enabled", havingValue = "true")
@EnableConfigurationProperties(GrpcServerProperties.class)
public class CalendarGrpcConfiguration {

    @Bean
    CalendarGrpcServerLifecycle calendarGrpcServerLifecycle(
            CalendarMetricsGrpcService metricsService, GrpcServerProperties properties) {
        return new CalendarGrpcServerLifecycle(metricsService, properties);
    }
}
