package com.lifeos.media.config;

import java.time.Duration;
import org.apache.coyote.ProtocolHandler;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Enforces a direct-service connection/upload deadline independently of the gateway. */
@Configuration
public class MediaInboundRequestConfiguration {

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> mediaInboundRequestTimeoutCustomizer(MediaProperties properties) {
        return factory -> factory.addConnectorCustomizers(
                connector -> configureConnector(connector.getProtocolHandler(), properties.getInboundRequestTimeout()));
    }

    static void configureConnector(ProtocolHandler handler, Duration timeout) {
        if (!(handler instanceof AbstractHttp11Protocol<?> protocol)) {
            throw new IllegalStateException("media-service requires a Tomcat HTTP/1.1 connector");
        }
        int milliseconds = timeoutMillis(timeout);
        protocol.setConnectionTimeout(milliseconds);
        protocol.setKeepAliveTimeout(milliseconds);
        protocol.setConnectionUploadTimeout(milliseconds);
        protocol.setDisableUploadTimeout(false);
    }

    private static int timeoutMillis(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("media inbound request timeout must be positive");
        }
        try {
            int milliseconds = Math.toIntExact(timeout.toMillis());
            if (milliseconds < 1) {
                throw new IllegalArgumentException("media inbound request timeout must be at least one millisecond");
            }
            return milliseconds;
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("media inbound request timeout is too large", exception);
        }
    }
}
