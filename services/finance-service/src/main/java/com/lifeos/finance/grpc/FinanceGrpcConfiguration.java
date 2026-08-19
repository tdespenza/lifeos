package com.lifeos.finance.grpc;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "grpc.server.enabled", havingValue = "true")
@EnableConfigurationProperties(GrpcServerProperties.class)
public class FinanceGrpcConfiguration {

    @Bean
    FinanceGrpcServerLifecycle financeGrpcServerLifecycle(
            FinanceMetricsGrpcService metricsService, GrpcServerProperties properties) {
        return new FinanceGrpcServerLifecycle(metricsService, properties);
    }
}
