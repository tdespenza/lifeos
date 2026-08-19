package com.lifeos.media.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.lifeos.media.observability.RequestContext;
import java.lang.ScopedValue;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Verifies Media sends exact registered V2 facts and fails closed on unusable Identity answers. */
class RestClientMediaAccessServiceTest {

    private static final String IDENTITY_URL = "https://identity.test";
    private static final String ACCESS_TOKEN_PROOF =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private MockRestServiceServer identityServer;
    private RestClientMediaAccessService accessService;

    @BeforeEach
    void setUp() {
        MediaIdentityProperties properties = new MediaIdentityProperties();
        properties.setBaseUrl(IDENTITY_URL);
        properties.setWorkloadIdentity("media-service");
        properties.setWorkloadToken("test-workload-token");
        properties.setExpectedPolicyVersion("v2");
        RestClient.Builder builder = RestClient.builder().baseUrl(IDENTITY_URL);
        identityServer = MockRestServiceServer.bindTo(builder).build();
        accessService = new RestClientMediaAccessService(builder.build(), properties);
    }

    @Test
    void authenticateForwardsOnlyBearerAndWorkloadCredentials() {
        UUID accountId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        identityServer.expect(requestTo(IDENTITY_URL + "/api/v1/auth/validate"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer raw-access-token"))
                .andExpect(header("X-LifeOS-Workload-Identity", "media-service"))
                .andExpect(header("X-LifeOS-Workload-Token", "test-workload-token"))
                .andRespond(withSuccess(subjectJson(accountId, sessionId), MediaType.APPLICATION_JSON));

        MediaSubject subject = accessService.authenticate("Bearer raw-access-token");

        assertThat(subject.accountId()).isEqualTo(accountId);
        assertThat(subject.sessionId()).isEqualTo(sessionId);
        identityServer.verify();
    }

    @Test
    void uploadDecisionSendsExactV2MediaAssetFactsAndCorrelation() {
        MediaSubject subject = subject();
        UUID assetId = UUID.randomUUID();
        MediaAuthorizationResource resource = MediaAuthorizationResource.existingAsset(assetId, subject.accountId(), subject);
        String correlationId = "11111111-1111-4111-8111-111111111111";
        identityServer.expect(requestTo(IDENTITY_URL + "/api/v1/internal/authorization/decisions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Correlation-ID", correlationId))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "subjectId":"%s",
                          "sessionId":"%s",
                          "accessTokenProof":"%s",
                          "action":"media:asset-upload",
                          "resource":{
                            "resourceType":"media-asset",
                            "resourceId":"%s",
                            "tenantId":"%s",
                            "attributes":{"ownerAccountId":"%s","resourceExists":"true"}
                          },
                          "expectedPolicyVersion":"v2"
                        }
                        """.formatted(
                        subject.accountId(),
                        subject.sessionId(),
                        subject.accessTokenProof(),
                        assetId,
                        subject.tenantId(),
                        subject.accountId())))
                .andRespond(allow());

        ScopedValue.where(RequestContext.CORRELATION_ID, correlationId)
                .run(() -> accessService.authorize(subject, MediaAuthorizationActions.ASSET_UPLOAD, resource));

        identityServer.verify();
    }

    @Test
    void deterministicDenyAndPolicyUnavailabilityRemainSeparated() {
        MediaSubject subject = subject();
        MediaAuthorizationResource collection = MediaAuthorizationResource.collection(subject);
        identityServer.expect(requestTo(IDENTITY_URL + "/api/v1/internal/authorization/decisions"))
                .andRespond(withSuccess(
                        """
                        {"outcome":"DENY","reasonCode":"MISSING_ROLE","policyVersion":"v2","expiresAt":"2099-01-01T00:00:00Z"}
                        """,
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> accessService.authorize(subject, MediaAuthorizationActions.ASSET_LIST, collection))
                .isInstanceOf(MediaAuthorizationDenied.class);
        identityServer.verify();

        identityServer.reset();
        identityServer.expect(requestTo(IDENTITY_URL + "/api/v1/internal/authorization/decisions"))
                .andRespond(withSuccess(
                        """
                        {"outcome":"DENY","reasonCode":"POLICY_UNAVAILABLE","policyVersion":"v2","expiresAt":"2099-01-01T00:00:00Z"}
                        """,
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> accessService.authorize(subject, MediaAuthorizationActions.ASSET_LIST, collection))
                .isInstanceOf(MediaAuthorizationDependencyUnavailable.class);
        identityServer.verify();
    }

    private static MediaSubject subject() {
        return new MediaSubject(UUID.randomUUID(), UUID.randomUUID(), "password", ACCESS_TOKEN_PROOF);
    }

    private static String subjectJson(UUID accountId, UUID sessionId) {
        return """
                {"accountId":"%s","sessionId":"%s","authenticationMethod":"password","accessTokenProof":"%s"}
                """.formatted(accountId, sessionId, ACCESS_TOKEN_PROOF);
    }

    private static org.springframework.test.web.client.response.DefaultResponseCreator allow() {
        return withSuccess(
                """
                {"outcome":"ALLOW","reasonCode":"ALLOWED","policyVersion":"v2","expiresAt":"2099-01-01T00:00:00Z"}
                """,
                MediaType.APPLICATION_JSON);
    }
}
