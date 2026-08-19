package com.lifeos.gateway.config;

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

class GatewayInboundRequestConfigurationTest {

    @Test
    void registersTheSameBoundedTimeoutForInitialKeepAliveAndBodyUploadReads() {
        GatewayProperties properties = new GatewayProperties();
        properties.setInboundRequestTimeout(Duration.ofSeconds(7));
        GatewayInboundRequestConfiguration configuration = new GatewayInboundRequestConfiguration();
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();

        configuration.gatewayInboundRequestTimeoutCustomizer(properties).customize(factory);
        Connector connector = new Connector();
        factory.getTomcatConnectorCustomizers().forEach(customizer -> customizer.customize(connector));
        Http11NioProtocol protocol = (Http11NioProtocol) connector.getProtocolHandler();

        assertThat(protocol.getConnectionTimeout()).isEqualTo(7_000);
        assertThat(protocol.getKeepAliveTimeout()).isEqualTo(7_000);
        assertThat(protocol.getConnectionUploadTimeout()).isEqualTo(7_000);
        assertThat(protocol.getDisableUploadTimeout()).isFalse();
    }

    @Test
    void failsClosedWhenTheConnectorCannotEnforceTheRequestBodyTimeout() {
        ProtocolHandler unsupportedHandler = mock(ProtocolHandler.class);

        assertThatIllegalStateException()
                .isThrownBy(() -> GatewayInboundRequestConfiguration.configureConnector(
                        unsupportedHandler, Duration.ofSeconds(7)))
                .withMessageContaining("HTTP/1.1");
    }

    @Test
    void rejectsTimeoutsThatTomcatCannotRepresent() {
        Http11NioProtocol protocol = new Http11NioProtocol();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> GatewayInboundRequestConfiguration.configureConnector(
                        protocol, Duration.ofNanos(999_999)))
                .withMessageContaining("at least one millisecond");
    }
}
