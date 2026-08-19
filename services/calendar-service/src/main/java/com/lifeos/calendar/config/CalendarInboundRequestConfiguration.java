package com.lifeos.calendar.config;

import java.time.Duration;
import org.apache.coyote.ProtocolHandler;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Enforces Calendar's bounded direct-service connection and upload timeout. */
@Configuration
public class CalendarInboundRequestConfiguration {

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> calendarInboundRequestTimeoutCustomizer(
            CalendarProperties properties) {
        return factory -> factory.addConnectorCustomizers(
                connector -> configureConnector(connector.getProtocolHandler(), properties.getInboundRequestTimeout()));
    }

    static void configureConnector(ProtocolHandler handler, Duration timeout) {
        if (!(handler instanceof AbstractHttp11Protocol<?> protocol)) {
            throw new IllegalStateException("calendar-service requires a Tomcat HTTP/1.1 connector");
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
            int value = Math.toIntExact(timeout.toMillis());
            if (value <= 0) {
                throw new IllegalArgumentException("inbound request timeout must be at least one millisecond");
            }
            return value;
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("inbound request timeout is too large for Tomcat", exception);
        }
    }
}
