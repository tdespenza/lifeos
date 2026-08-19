package com.lifeos.finance.config;

import java.time.Duration;
import org.apache.coyote.ProtocolHandler;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Applies bounded direct-service HTTP timeouts before application deserialization starts. */
@Configuration
public class FinanceInboundRequestConfiguration {

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> financeInboundRequestTimeoutCustomizer(
            FinanceServiceProperties properties) {
        Duration timeout = properties.getInboundRequestTimeout();
        return factory -> factory.addConnectorCustomizers(
                connector -> configureConnector(connector.getProtocolHandler(), timeout));
    }

    static void configureConnector(ProtocolHandler handler, Duration timeout) {
        if (!(handler instanceof AbstractHttp11Protocol<?> protocol)) {
            throw new IllegalStateException(
                    "finance-service requires a Tomcat HTTP/1.1 connector to enforce inbound request-body timeouts");
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
