package com.lifeos.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifeos.gateway.config.GatewayAuthenticationProperties;
import com.lifeos.gateway.routing.GatewayRoute;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class GatewayAuthenticationServiceTest {

    private static final GatewayRoute ROUTE = new GatewayRoute(
            "goals", "/api/v1/goals", URI.create("https://task-goal.test"), true, Set.of());

    @Test
    void rejectsAdditionalValidationWhenTheBulkheadIsFull() throws Exception {
        GatewayAuthenticationProperties properties = new GatewayAuthenticationProperties();
        properties.setBaseUrl("https://identity.test");
        properties.setWorkloadIdentity("gateway-service");
        properties.setWorkloadToken("test-gateway-workload-token");

        CountDownLatch firstValidationStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstValidation = new CountDownLatch(1);
        GatewayAuthenticatedSubject subject = new GatewayAuthenticatedSubject(
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), "PASSWORD");
        GatewayAuthenticationClient client = new GatewayAuthenticationClient(
                RestClient.builder().baseUrl(properties.getBaseUrl()).build(), properties) {
            @Override
            public GatewayAuthenticatedSubject authenticate(String authorizationHeader) {
                firstValidationStarted.countDown();
                try {
                    releaseFirstValidation.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new GatewayAuthenticationDependencyUnavailableException(exception);
                }
                return subject;
            }
        };
        GatewayAuthenticationService service = new GatewayAuthenticationService(
                client, new GatewayAuthenticationMetrics(new SimpleMeterRegistry()), 1);

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<GatewayAuthenticatedSubject> first = executor.submit(
                    () -> service.authenticate(ROUTE, "Bearer first"));
            assertThat(firstValidationStarted.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> service.authenticate(ROUTE, "Bearer second"))
                    .isInstanceOf(GatewayAuthenticationDependencyUnavailableException.class)
                    .satisfies(exception -> assertThat(
                                    ((GatewayAuthenticationDependencyUnavailableException) exception).reasonCode())
                            .isEqualTo(GatewayAuthenticationDependencyUnavailableException.REASON_BULKHEAD_REJECTED));

            releaseFirstValidation.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(subject);
        }
    }
}
