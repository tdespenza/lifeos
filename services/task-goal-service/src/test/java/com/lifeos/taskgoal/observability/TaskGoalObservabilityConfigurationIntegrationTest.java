package com.lifeos.taskgoal.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifeos.taskgoal.authorization.RestClientTaskAccessService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

/** Integration check for the task-goal reference telemetry contract. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:task-goal-observability;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=task-goal-test-password",
    // This checks telemetry wiring rather than Flyway migrations, which have their own test suite.
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false",
    "identity.workload-token=integration-test-workload-token"
})
@AutoConfigureObservability
class TaskGoalObservabilityConfigurationIntegrationTest {

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private Tracer tracer;

    @Autowired
    private RestClientTaskAccessService accessService;

    @Autowired
    private Environment environment;

    @Test
    void configuresObservedClientPrometheusTracingAndPrivateManagementSurface() {
        assertThat(accessService).isNotNull();
        assertThat(meterRegistry).isInstanceOf(PrometheusMeterRegistry.class);
        assertThat(tracer).isNotNull();
        assertThat(environment.getProperty("management.server.port")).isEqualTo("9082");
        assertThat(environment.getProperty("management.server.address")).isEqualTo("127.0.0.1");
        assertThat(environment.getProperty("management.endpoint.prometheus.access")).isEqualTo("unrestricted");
        assertThat(environment.getProperty("management.otlp.tracing.endpoint"))
                .isEqualTo("http://localhost:4318/v1/traces");
        assertThat(environment.getProperty("logging.structured.format.console")).isEqualTo("ecs");
    }
}
