package com.lifeos.taskgoal.config;

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

/** Focused connector tests for the Task/Goal service's inbound deadline policy. */
class TaskGoalInboundRequestConfigurationTest {

    @Test
    void registersTheSameBoundedTimeoutForInitialKeepAliveAndBodyUploadReads() {
        TaskGoalServiceProperties properties = new TaskGoalServiceProperties();
        properties.setInboundRequestTimeout(Duration.ofSeconds(7));
        TaskGoalInboundRequestConfiguration configuration = new TaskGoalInboundRequestConfiguration();
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();

        configuration.taskGoalInboundRequestTimeoutCustomizer(properties).customize(factory);
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
                .isThrownBy(() -> TaskGoalInboundRequestConfiguration.configureConnector(
                        unsupportedHandler, Duration.ofSeconds(7)))
                .withMessageContaining("HTTP/1.1");
    }

    @Test
    void rejectsTimeoutsThatTomcatCannotRepresent() {
        Http11NioProtocol protocol = new Http11NioProtocol();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> TaskGoalInboundRequestConfiguration.configureConnector(
                        protocol, Duration.ofNanos(999_999)))
                .withMessageContaining("at least one millisecond");
    }
}
