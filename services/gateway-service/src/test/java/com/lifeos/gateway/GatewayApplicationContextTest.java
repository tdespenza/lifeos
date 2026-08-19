package com.lifeos.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifeos.gateway.config.GatewayProperties;
import com.lifeos.gateway.graphql.DashboardGrpcProperties;
import com.lifeos.gateway.routing.GatewayRouteTable;
import jakarta.validation.Validator;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "gateway.authentication.workload-token=test-only-gateway-workload-token",
            "gateway.rate-limit.key-secret=test-only-rate-limit-secret"
        })
class GatewayApplicationContextTest {

    @Autowired
    private GatewayRouteTable routeTable;

    @Autowired
    private GatewayProperties properties;

    @Autowired
    private DashboardGrpcProperties dashboardGrpcProperties;

    @Autowired
    private Validator validator;

    @Test
    void startsWithTheDeploymentOwnedPublicRouteTable() {
        assertThat(routeTable.resolve("/api/v1/accounts")).isPresent();
        assertThat(routeTable.resolve("/api/v1/auth/login")).isPresent();
        assertThat(routeTable.resolve("/api/v1/auth/sessions")).get()
                .satisfies(route -> assertThat(route.requiresAuthentication("GET")).isTrue());
        assertThat(routeTable.resolve("/api/v1/goals")).isPresent();
        assertThat(routeTable.resolve("/api/v1/goals")).get()
                .extracting(route -> route.authenticationRequired())
                .isEqualTo(true);
        assertThat(routeTable.resolve("/api/v1/tasks/00000000-0000-4000-8000-000000000001")).get()
                .satisfies(route -> {
                    assertThat(route.id()).isEqualTo("task-goal-tasks");
                    assertThat(route.upstream().toString()).isEqualTo("http://localhost:8082");
                    assertThat(route.requiresAuthentication("POST")).isTrue();
                });
        assertThat(routeTable.resolve("/api/v1/dependencies/execution-order")).get()
                .satisfies(route -> {
                    assertThat(route.id()).isEqualTo("task-goal-dependencies");
                    assertThat(route.upstream().toString()).isEqualTo("http://localhost:8082");
                    assertThat(route.requiresAuthentication("GET")).isTrue();
                });
        assertThat(routeTable.resolve("/api/v1/habits")).get()
                .satisfies(route -> {
                    assertThat(route.id()).isEqualTo("task-goal-habits");
                    assertThat(route.upstream().toString()).isEqualTo("http://localhost:8082");
                    assertThat(route.requiresAuthentication("POST")).isTrue();
                });
        assertThat(routeTable.resolve("/api/v1/routines")).get()
                .satisfies(route -> {
                    assertThat(route.id()).isEqualTo("task-goal-routines");
                    assertThat(route.upstream().toString()).isEqualTo("http://localhost:8082");
                    assertThat(route.requiresAuthentication("POST")).isTrue();
                });
        assertThat(routeTable.resolve("/api/v1/profiles")).get()
                .satisfies(route -> {
                    assertThat(route.id()).isEqualTo("profile-profiles");
                    assertThat(route.upstream().toString()).isEqualTo("http://localhost:8083");
                    assertThat(route.requiresAuthentication("GET")).isTrue();
                });
        assertThat(routeTable.resolve("/api/v1/households/abc")).get()
                .satisfies(route -> {
                    assertThat(route.id()).isEqualTo("profile-households");
                    assertThat(route.upstream().toString()).isEqualTo("http://localhost:8083");
                    assertThat(route.requiresAuthentication("GET")).isTrue();
                });
        assertThat(routeTable.resolve("/api/v1/calendar/events")).get()
                .satisfies(route -> {
                    assertThat(route.id()).isEqualTo("calendar");
                    assertThat(route.upstream().toString()).isEqualTo("http://localhost:8085");
                    assertThat(route.requiresAuthentication("POST")).isTrue();
                });
        assertThat(routeTable.resolve("/api/v1/finance/transactions")).get()
                .satisfies(route -> {
                    assertThat(route.id()).isEqualTo("finance");
                    assertThat(route.upstream().toString()).isEqualTo("http://localhost:8086");
                    assertThat(route.requiresAuthentication("POST")).isTrue();
                });
        assertThat(routeTable.resolve("/api/v1/documents")).get()
                .satisfies(route -> {
                    assertThat(route.id()).isEqualTo("document-vault");
                    assertThat(route.upstream().toString()).isEqualTo("http://localhost:8088");
                    assertThat(route.requiresAuthentication("POST")).isTrue();
                    assertThat(route.isExactDocumentUploadRequest("/api/v1/documents", "POST"))
                            .isTrue();
                    assertThat(route.isExactDocumentUploadRequest("/api/v1/documents/123", "POST"))
                            .isFalse();
                    assertThat(route.isExactDocumentUploadRequest("/api/v1/documents", "PUT"))
                            .isFalse();
                });
        assertThat(routeTable.resolve("/api/v1/assistant/conversations")).get()
                .satisfies(route -> {
                    assertThat(route.id()).isEqualTo("ai-assistant");
                    assertThat(route.upstream().toString()).isEqualTo("http://localhost:8090");
                    assertThat(route.requiresAuthentication("GET")).isTrue();
                    assertThat(route.requiresAuthentication("POST")).isTrue();
                });
        assertThat(routeTable.resolve("/api/v1/analytics/dashboard")).get()
                .satisfies(route -> {
                    assertThat(route.id()).isEqualTo("analytics");
                    assertThat(route.upstream().toString()).isEqualTo("http://localhost:8091");
                    assertThat(route.requiresAuthentication("GET")).isTrue();
                });
        assertThat(routeTable.resolve("/api/v1/notifications")).get()
                .satisfies(route -> {
                    assertThat(route.id()).isEqualTo("notification-history");
                    assertThat(route.upstream().toString()).isEqualTo("http://localhost:8084");
                    assertThat(route.streaming()).isFalse();
                    assertThat(route.requiresAuthentication("GET")).isTrue();
                });
        assertThat(routeTable.resolve("/api/v1/notification-endpoints")).get()
                .satisfies(route -> {
                    assertThat(route.id()).isEqualTo("notification-endpoints");
                    assertThat(route.upstream().toString()).isEqualTo("http://localhost:8084");
                    assertThat(route.requiresAuthentication("POST")).isTrue();
                });
        assertThat(routeTable.resolve("/api/v1/notifications/stream")).get()
                .satisfies(route -> {
                    assertThat(route.id()).isEqualTo("notification-stream");
                    assertThat(route.streaming()).isTrue();
                    assertThat(route.isExactStreamingRequest("/api/v1/notifications/stream", "GET"))
                            .isTrue();
                });
        assertThat(routeTable.resolve("/api/v1/accounts/00000000-0000-4000-8000-000000000001")).get()
                .satisfies(route -> {
                    assertThat(route.requiresAuthentication("/api/v1/accounts", "POST")).isFalse();
                    assertThat(route.requiresAuthentication(
                            "/api/v1/accounts/00000000-0000-4000-8000-000000000001", "POST")).isTrue();
                    assertThat(route.requiresAuthentication(
                            "/api/v1/accounts/00000000-0000-4000-8000-000000000001", "PUT")).isTrue();
                    assertThat(route.requiresAuthentication(
                            "/api/v1/accounts/00000000-0000-4000-8000-000000000001", "PATCH")).isTrue();
                    assertThat(route.requiresAuthentication(
                            "/api/v1/accounts/00000000-0000-4000-8000-000000000001", "DELETE")).isTrue();
                });
        assertThat(routeTable.resolve("/api/v1/internal/authorization/decisions")).isEmpty();
        assertThat(properties.getReadTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.getAiAssistant().getReadTimeout()).isEqualTo(Duration.ofSeconds(12));
        assertThat(dashboardGrpcProperties.isEnabled()).isFalse();
        assertThat(dashboardGrpcProperties.getDeadline()).isEqualTo(Duration.ofSeconds(2));
        assertThat(dashboardGrpcProperties.getTask().getPort()).isEqualTo(10_082);
    }

    @Test
    void rejectsTimeoutsLongerThanSixtySecondsDuringConfigurationValidation() {
        properties.setReadTimeout(Duration.ofSeconds(61));

        assertThat(validator.validate(properties))
                .anyMatch(violation -> violation.getMessage().contains("no greater than 60 seconds"));
    }

    @Test
    void rejectsStreamingLifetimesLongerThanOneHourDuringConfigurationValidation() {
        properties.getStreaming().setReadLifetime(Duration.ofHours(1).plusSeconds(1));

        assertThat(validator.validate(properties))
                .anyMatch(violation -> violation.getPropertyPath().toString()
                        .contains("streaming.timeoutsValid"));
    }

    @Test
    void rejectsDocumentUploadTimeoutsShorterThanTheirConnectionDeadlineDuringConfigurationValidation() {
        properties.getDocumentUpload().setConnectTimeout(Duration.ofSeconds(3));
        properties.getDocumentUpload().setReadTimeout(Duration.ofSeconds(2));

        assertThat(validator.validate(properties))
                .anyMatch(violation -> violation.getPropertyPath().toString()
                        .contains("documentUpload.timeoutsValid"));
    }
}
