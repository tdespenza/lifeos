package com.lifeos.taskgoal.config;

import java.time.Duration;
import org.apache.coyote.ProtocolHandler;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Applies the Task/Goal service's bounded inbound timeout policy to Tomcat's HTTP connector.
 *
 * <p>Spring Boot exposes Tomcat's initial connection timeout but not its independent request-body
 * upload timeout. Leaving that timeout disabled permits a slow client to retain a servlet request
 * while it stops making upload progress. The service applies one deployment-owned bound to initial
 * request reads, keep-alive reads, and request-body uploads.
 */
@Configuration
public class TaskGoalInboundRequestConfiguration {

    /**
     * Installs the inbound timeout policy for every Task/Goal Tomcat connector.
     *
     * @param properties Task/Goal service resource-bound configuration
     * @return Tomcat factory customizer
     */
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> taskGoalInboundRequestTimeoutCustomizer(
            TaskGoalServiceProperties properties) {
        Duration timeout = properties.getInboundRequestTimeout();
        return factory -> factory.addConnectorCustomizers(
                connector -> configureConnector(connector.getProtocolHandler(), timeout));
    }

    /**
     * Applies the timeout policy to one Tomcat connector. Package visibility permits focused
     * verification without opening a listener.
     *
     * @param handler Tomcat connector protocol handler
     * @param timeout bounded Task/Goal service timeout
     */
    static void configureConnector(ProtocolHandler handler, Duration timeout) {
        if (!(handler instanceof AbstractHttp11Protocol<?> protocol)) {
            throw new IllegalStateException(
                    "task-goal-service requires a Tomcat HTTP/1.1 connector to enforce inbound request-body timeouts");
        }
        int timeoutMillis = timeoutMillis(timeout);
        protocol.setConnectionTimeout(timeoutMillis);
        protocol.setKeepAliveTimeout(timeoutMillis);
        protocol.setConnectionUploadTimeout(timeoutMillis);
        protocol.setDisableUploadTimeout(false);
    }

    private static int timeoutMillis(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("inbound request timeout must be positive");
        }
        try {
            int timeoutMillis = Math.toIntExact(timeout.toMillis());
            if (timeoutMillis <= 0) {
                throw new IllegalArgumentException("inbound request timeout must be at least one millisecond");
            }
            return timeoutMillis;
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("inbound request timeout is too large for Tomcat", exception);
        }
    }
}
