package com.lifeos.trustledger.config;

import java.time.Duration;
import org.apache.coyote.ProtocolHandler;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Binds every Tomcat read and upload phase to the configured inbound request deadline. */
@Configuration
public class TrustLedgerInboundRequestConfiguration {

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> trustLedgerInboundRequestTimeoutCustomizer(
            TrustLedgerServiceProperties properties) {
        Duration timeout = properties.getInboundRequestTimeout();
        return factory -> factory.addConnectorCustomizers(
                connector -> configureConnector(connector.getProtocolHandler(), timeout));
    }

    static void configureConnector(ProtocolHandler handler, Duration timeout) {
        if (!(handler instanceof AbstractHttp11Protocol<?> protocol)) {
            throw new IllegalStateException(
                    "trust-ledger-service requires a Tomcat HTTP/1.1 connector for bounded uploads");
        }
        int timeoutMillis = toMillis(timeout);
        protocol.setConnectionTimeout(timeoutMillis);
        protocol.setKeepAliveTimeout(timeoutMillis);
        protocol.setConnectionUploadTimeout(timeoutMillis);
        protocol.setDisableUploadTimeout(false);
    }

    private static int toMillis(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("inbound request timeout must be positive");
        }
        try {
            int milliseconds = Math.toIntExact(timeout.toMillis());
            if (milliseconds <= 0) {
                throw new IllegalArgumentException("inbound request timeout must be at least one millisecond");
            }
            return milliseconds;
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("inbound request timeout is too large for Tomcat", exception);
        }
    }
}
