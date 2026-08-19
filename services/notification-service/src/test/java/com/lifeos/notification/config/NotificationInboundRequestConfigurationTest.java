package com.lifeos.notification.config;

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

/** Connector checks for notification request and upload deadline enforcement. */
class NotificationInboundRequestConfigurationTest {

    @Test
    void appliesOneBoundedTimeoutToConnectionKeepAliveAndUploadReads() {
        NotificationProperties properties = new NotificationProperties();
        properties.setInboundRequestTimeout(Duration.ofSeconds(7));
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();

        new NotificationInboundRequestConfiguration()
                .notificationInboundRequestTimeoutCustomizer(properties)
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
                .isThrownBy(() -> NotificationInboundRequestConfiguration.configureConnector(
                        mock(ProtocolHandler.class), Duration.ofSeconds(7)))
                .withMessageContaining("HTTP/1.1");
    }

    @Test
    void rejectsSubMillisecondTimeouts() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> NotificationInboundRequestConfiguration.configureConnector(
                        new Http11NioProtocol(), Duration.ofNanos(999_999)))
                .withMessageContaining("at least one millisecond");
    }
}
