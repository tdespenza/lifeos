package com.lifeos.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

/**
 * Verifies the provider token exchange and signed ID-token validation boundary.
 */
class RestClientOidcProviderClientTest {

    private static final String CODE = "authorization-code";
    private static final String VERIFIER = "a-verifier-with-43-characters-012345678901234";
    private static final String NONCE = "callback-nonce";

    private RSAKey signingKey;
    private IdentityAuthProperties.Provider provider;
    private MockRestServiceServer tokenServer;
    private MockRestServiceServer jwkServer;
    private RestClientOidcProviderClient client;

    @BeforeEach
    void setUp() throws JOSEException {
        signingKey = new RSAKeyGenerator(2048).keyID("oidc-test-key").generate();
        provider = new IdentityAuthProperties.Provider();
        provider.setIssuer("https://issuer.example");
        provider.setAuthorizationUri("https://issuer.example/authorize");
        provider.setTokenUri("https://issuer.example/token");
        provider.setJwkSetUri("https://issuer.example/jwks");
        provider.setClientId("lifeos-client");
        provider.setClientSecret("provider-secret");
        provider.setRedirectUri("https://lifeos.example/api/v1/auth/oidc/example/callback");

        RestClient.Builder tokenClientBuilder = RestClient.builder();
        tokenServer = MockRestServiceServer.bindTo(tokenClientBuilder).build();
        RestTemplate jwkRestTemplate = new RestTemplate();
        jwkServer = MockRestServiceServer.bindTo(jwkRestTemplate).build();
        client = new RestClientOidcProviderClient(tokenClientBuilder.build(), jwkRestTemplate);
    }

    @Test
    void exchangesCodeAndReturnsOnlyValidatedIdentityClaims() throws Exception {
        SignedJWT token = token(validClaims());
        expectProviderExchange(token);

        OidcIdentity identity = client.exchangeAndValidate(provider, CODE, VERIFIER, NONCE);

        assertThat(identity.subject()).isEqualTo("subject-1");
        assertThat(identity.email()).isEqualTo("ada@example.com");
        assertThat(identity.displayName()).isEqualTo("Ada Lovelace");
        tokenServer.verify();
        jwkServer.verify();
    }

    @Test
    void rejectsAnIdTokenWithWrongIssuer() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder(validClaims())
                .issuer("https://another-issuer.example")
                .build();
        expectProviderExchange(token(claims));

        assertThatThrownBy(() -> client.exchangeAndValidate(provider, CODE, VERIFIER, NONCE))
                .isInstanceOf(OidcAuthenticationFailureException.class);

        tokenServer.verify();
        jwkServer.verify();
    }

    @Test
    void rejectsAnIdTokenWithWrongAudience() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder(validClaims())
                .audience("another-client")
                .build();
        expectProviderExchange(token(claims));

        assertThatThrownBy(() -> client.exchangeAndValidate(provider, CODE, VERIFIER, NONCE))
                .isInstanceOf(OidcAuthenticationFailureException.class);

        tokenServer.verify();
        jwkServer.verify();
    }

    @Test
    void rejectsAnIdTokenWithWrongNonce() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder(validClaims())
                .claim("nonce", "different-nonce")
                .build();
        expectProviderExchange(token(claims));

        assertThatThrownBy(() -> client.exchangeAndValidate(provider, CODE, VERIFIER, NONCE))
                .isInstanceOf(OidcAuthenticationFailureException.class);

        tokenServer.verify();
        jwkServer.verify();
    }

    @Test
    void rejectsAnIdTokenWithUnverifiedEmail() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder(validClaims())
                .claim("email_verified", false)
                .build();
        expectProviderExchange(token(claims));

        assertThatThrownBy(() -> client.exchangeAndValidate(provider, CODE, VERIFIER, NONCE))
                .isInstanceOf(OidcAuthenticationFailureException.class);

        tokenServer.verify();
        jwkServer.verify();
    }

    @Test
    void rejectsAnIdTokenWithAnUntrustedSignature() throws Exception {
        RSAKey otherSigningKey = new RSAKeyGenerator(2048).keyID("untrusted-key").generate();
        expectProviderExchange(token(validClaims(), otherSigningKey));

        assertThatThrownBy(() -> client.exchangeAndValidate(provider, CODE, VERIFIER, NONCE))
                .isInstanceOf(OidcAuthenticationFailureException.class);

        tokenServer.verify();
        jwkServer.verify();
    }

    private void expectProviderExchange(SignedJWT token) {
        String verifier = URLEncoder.encode(VERIFIER, StandardCharsets.UTF_8);
        tokenServer.expect(MockRestRequestMatchers.requestTo(provider.getTokenUri()))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.POST))
                .andExpect(MockRestRequestMatchers.content().string(containsString("code_verifier=" + verifier)))
                .andRespond(MockRestResponseCreators.withSuccess(
                        "{\"id_token\":\"" + token.serialize()
                                + "\",\"access_token\":\"provider-access\",\"refresh_token\":\"provider-refresh\"}",
                        MediaType.APPLICATION_JSON));
        jwkServer.expect(ExpectedCount.manyTimes(),
                MockRestRequestMatchers.requestTo(provider.getJwkSetUri()))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.GET))
                .andRespond(MockRestResponseCreators.withSuccess(
                        new JWKSet(signingKey.toPublicJWK()).toString(), MediaType.APPLICATION_JSON));
    }

    private JWTClaimsSet validClaims() {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(provider.getIssuer())
                .audience(provider.getClientId())
                .subject("subject-1")
                .claim("email", "ada@example.com")
                .claim("email_verified", true)
                .claim("name", "Ada Lovelace")
                .claim("nonce", NONCE)
                .issueTime(Date.from(now.minusSeconds(5)))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .build();
    }

    private SignedJWT token(JWTClaimsSet claims) throws JOSEException {
        return token(claims, signingKey);
    }

    private SignedJWT token(JWTClaimsSet claims, RSAKey key) throws JOSEException {
        SignedJWT token = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        token.sign(new RSASSASigner(key.toPrivateKey()));
        return token;
    }
}
