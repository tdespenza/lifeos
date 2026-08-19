package com.lifeos.gateway.config;

import java.time.Duration;
import org.apache.coyote.ProtocolHandler;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Applies the gateway's bounded inbound timeout policy to Tomcat's HTTP connector.
 *
 * <p>Spring Boot exposes Tomcat's initial connection timeout but not its separate request-body
 * upload timeout. Tomcat disables that upload timeout by default and otherwise allows five
 * minutes, which is unsuitable for a bounded public gateway: a stalled body read would retain a
 * request-body admission permit. This configuration enables and bounds the upload timeout using
 * the same deployment-owned value as request-line/header and keep-alive reads.
 */
@Configuration
public class GatewayInboundRequestConfiguration {

    /**
     * Installs the inbound timeout policy for every gateway Tomcat connector.
     *
     * @param properties gateway timeout configuration
     * @return Tomcat factory customizer
     */
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> gatewayInboundRequestTimeoutCustomizer(
            GatewayProperties properties) {
        Duration timeout = properties.getInboundRequestTimeout();
        return factory -> factory.addConnectorCustomizers(
                connector -> configureConnector(connector.getProtocolHandler(), timeout));
    }

    /**
     * Applies the timeout policy to one Tomcat connector. Package visibility permits a focused
     * unit test without starting a network listener.
     *
     * @param handler Tomcat connector protocol handler
     * @param timeout bounded gateway timeout
     */
    static void configureConnector(ProtocolHandler handler, Duration timeout) {
        if (!(handler instanceof AbstractHttp11Protocol<?> protocol)) {
            throw new IllegalStateException(
                    "gateway requires a Tomcat HTTP/1.1 connector to enforce inbound request-body timeouts");
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
