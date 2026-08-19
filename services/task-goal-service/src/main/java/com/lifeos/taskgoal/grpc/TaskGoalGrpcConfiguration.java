package com.lifeos.taskgoal.grpc;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Configuration for the opt-in, mTLS-only internal Task metrics host. */
@Configuration
@ConditionalOnProperty(prefix = "grpc.server", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(GrpcServerProperties.class)
public class TaskGoalGrpcConfiguration {

    @Bean
    TaskGoalGrpcServerLifecycle taskGoalGrpcServerLifecycle(
            TaskMetricsGrpcService metricsService, GrpcServerProperties properties) {
        return new TaskGoalGrpcServerLifecycle(metricsService, properties);
    }
}
