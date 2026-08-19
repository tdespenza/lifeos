package com.lifeos.analytics.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import org.apache.catalina.connector.Connector;
import org.apache.coyote.ProtocolHandler;
import org.apache.coyote.http11.Http11NioProtocol;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;

/** Connector checks for analytics request deadline enforcement. */
class AnalyticsInboundRequestConfigurationTest {

    @Test
    void appliesOneBoundedTimeoutToConnectionKeepAliveAndUploadReads() {
        AnalyticsProperties properties = new AnalyticsProperties();
        properties.setInboundRequestTimeout(Duration.ofSeconds(7));
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();

        new AnalyticsInboundRequestConfiguration()
                .analyticsInboundRequestTimeoutCustomizer(properties)
                .customize(factory);
        Connector connector = new Connector();
        factory.getTomcatConnectorCustomizers().forEach(customizer -> customizer.customize(connector));
        Http11NioProtocol protocol = (Http11NioProtocol) connector.getProtocolHandler();

        assertThat(protocol.getConnectionTimeout()).isEqualTo(7_000);
        assertThat(protocol.getKeepAliveTimeout()).isEqualTo(7_000);
        assertThat(protocol.getConnectionUploadTimeout()).isEqualTo(7_000);
        assertThat(protocol.getDisableUploadTimeout()).isFalse();
    }

    @Test
    void failsClosedWhenTomcatCannotEnforceTheRequestDeadline() {
        assertThatIllegalStateException()
                .isThrownBy(() -> AnalyticsInboundRequestConfiguration.configureConnector(
                        mock(ProtocolHandler.class), Duration.ofSeconds(7)))
                .withMessageContaining("HTTP/1.1");
    }

    @Test
    void rejectsSubMillisecondTimeouts() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AnalyticsInboundRequestConfiguration.configureConnector(
                        new Http11NioProtocol(), Duration.ofNanos(999_999)))
                .withMessageContaining("at least one millisecond");
    }
}
