package com.lifeos.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.lifeos.gateway.config.GatewayAuthenticationProperties;
import com.lifeos.gateway.observability.RequestContext;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Verifies the gateway's fail-closed adapter to the identity validation authority. */
class GatewayAuthenticationClientTest {

    private static final String IDENTITY_URL = "https://identity.test";
    private static final String WORKLOAD_TOKEN = "test-gateway-workload-token";
    private static final String ACCESS_TOKEN_PROOF =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private MockRestServiceServer identityServer;
    private GatewayAuthenticationClient client;

    @BeforeEach
    void setUp() {
        GatewayAuthenticationProperties properties = new GatewayAuthenticationProperties();
        properties.setBaseUrl(IDENTITY_URL);
        properties.setWorkloadIdentity("gateway-service");
        properties.setWorkloadToken(WORKLOAD_TOKEN);
        RestClient.Builder builder = RestClient.builder().baseUrl(IDENTITY_URL);
        identityServer = MockRestServiceServer.bindTo(builder).build();
        client = new GatewayAuthenticationClient(builder.build(), properties);
    }

    @Test
    void rejectsMissingMalformedAndNonBearerHeadersBeforeAnyIdentityRequest() {
        assertThatThrownBy(() -> client.authenticate(null))
                .isInstanceOf(GatewayAuthenticationFailureException.class);
        assertThatThrownBy(() -> client.authenticate("Basic credentials"))
                .isInstanceOf(GatewayAuthenticationFailureException.class);
        assertThatThrownBy(() -> client.authenticate("Bearer"))
                .isInstanceOf(GatewayAuthenticationFailureException.class);
        assertThatThrownBy(() -> client.authenticate("Bearer token with spaces"))
                .isInstanceOf(GatewayAuthenticationFailureException.class);
        identityServer.verify();
    }

    @Test
    void sendsOnlyTheParsedBearerAndConfiguredWorkloadCredential() {
        UUID accountId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        identityServer.expect(requestTo(IDENTITY_URL + "/api/v1/auth/validate"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer signed-access-token"))
                .andExpect(header("X-LifeOS-Workload-Identity", "gateway-service"))
                .andExpect(header("X-LifeOS-Workload-Token", WORKLOAD_TOKEN))
                .andRespond(withSuccess("""
                        {"accountId":"%s","sessionId":"%s","authenticationMethod":"PASSWORD",
                         "accessTokenProof":"%s"}
                        """.formatted(accountId, sessionId, ACCESS_TOKEN_PROOF), MediaType.APPLICATION_JSON));

        GatewayAuthenticatedSubject subject = client.authenticate("Bearer signed-access-token");

        assertThat(subject.accountId()).isEqualTo(accountId);
        assertThat(subject.sessionId()).isEqualTo(sessionId);
        assertThat(subject.authenticationMethod()).isEqualTo("PASSWORD");
        identityServer.verify();
    }

    @Test
    void propagatesTheBoundCorrelationIdWithoutRetainingCredentialMaterial() throws Exception {
        UUID accountId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        String correlationId = "11111111-1111-4111-8111-111111111111";
        identityServer.expect(requestTo(IDENTITY_URL + "/api/v1/auth/validate"))
                .andExpect(header("X-Correlation-ID", correlationId))
                .andRespond(withSuccess("""
                        {"accountId":"%s","sessionId":"%s","authenticationMethod":"PASSWORD",
                         "accessTokenProof":"%s"}
                        """.formatted(accountId, sessionId, ACCESS_TOKEN_PROOF), MediaType.APPLICATION_JSON));

        GatewayAuthenticatedSubject subject = ScopedValue.where(RequestContext.CORRELATION_ID, correlationId)
                .call(() -> client.authenticate("Bearer signed-access-token"));

        assertThat(subject.toString()).isEqualTo("GatewayAuthenticatedSubject[redacted]");
        identityServer.verify();
    }

    @Test
    void treatsMalformedAuthorityResponsesAsUnavailable() {
        identityServer.expect(requestTo(IDENTITY_URL + "/api/v1/auth/validate"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.authenticate("Bearer signed-access-token"))
                .isInstanceOf(GatewayAuthenticationDependencyUnavailableException.class)
                .hasMessage(null);
        identityServer.verify();
    }
}
