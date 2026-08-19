package com.lifeos.analytics.grpc;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "grpc.server.enabled", havingValue = "true")
@EnableConfigurationProperties(AnalyticsGrpcServerProperties.class)
public class AnalyticsGrpcConfiguration {

    @Bean
    AnalyticsGrpcServerLifecycle analyticsGrpcServerLifecycle(
            AnalyticsDashboardGrpcService dashboardService, AnalyticsGrpcServerProperties properties) {
        return new AnalyticsGrpcServerLifecycle(dashboardService, properties);
    }
}
