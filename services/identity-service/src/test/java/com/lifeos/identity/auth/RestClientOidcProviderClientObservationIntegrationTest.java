package com.lifeos.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Verifies that the production OIDC client retains customizers from both Boot HTTP builders.
 *
 * <p>Spring Boot supplies its distributed-tracing propagation through the same builder
 * customization path exercised here. The isolated unit tests continue to use explicitly supplied
 * mock transports so they do not require an application context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(RestClientOidcProviderClientObservationIntegrationTest.BuilderMarkerConfiguration.class)
class RestClientOidcProviderClientObservationIntegrationTest {

    private static final String CODE = "authorization-code";
    private static final String VERIFIER = "a-verifier-with-43-characters-012345678901234";
    private static final String NONCE = "callback-nonce";
    private static final String TOKEN_BUILDER_MARKER = "X-Test-Token-Builder";
    private static final String JWK_BUILDER_MARKER = "X-Test-Jwk-Builder";

    private static RSAKey signingKey;

    @Autowired
    private RestClientOidcProviderClient client;

    private final AtomicReference<String> tokenBuilderMarker = new AtomicReference<>();
    private final AtomicReference<String> jwkBuilderMarker = new AtomicReference<>();
    private HttpServer providerServer;
    private IdentityAuthProperties.Provider provider;

    @BeforeAll
    static void generateSigningKey() throws JOSEException {
        signingKey = new RSAKeyGenerator(2048).keyID("oidc-observation-test-key").generate();
    }

    @BeforeEach
    void startProvider() throws IOException {
        providerServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        providerServer.createContext("/token", this::respondToTokenExchange);
        providerServer.createContext("/jwks", this::respondToJwkRequest);
        providerServer.start();

        String issuer = "http://127.0.0.1:" + providerServer.getAddress().getPort();
        provider = new IdentityAuthProperties.Provider();
        provider.setIssuer(issuer);
        provider.setAuthorizationUri(issuer + "/authorize");
        provider.setTokenUri(issuer + "/token");
        provider.setJwkSetUri(issuer + "/jwks");
        provider.setClientId("lifeos-client");
        provider.setClientSecret("provider-secret");
        provider.setRedirectUri("http://localhost:4200/api/v1/auth/oidc/example/callback");
    }

    @AfterEach
    void stopProvider() {
        if (providerServer != null) {
            providerServer.stop(0);
        }
    }

    @Test
    void productionClientUsesBothBootCustomizedTransports() throws Exception {
        OidcIdentity identity = client.exchangeAndValidate(provider, CODE, VERIFIER, NONCE);

        assertThat(identity.email()).isEqualTo("ada@example.com");
        assertThat(tokenBuilderMarker).hasValue("present");
        assertThat(jwkBuilderMarker).hasValue("present");
    }

    private void respondToTokenExchange(HttpExchange exchange) throws IOException {
        tokenBuilderMarker.set(exchange.getRequestHeaders().getFirst(TOKEN_BUILDER_MARKER));
        try {
            String response = "{\"id_token\":\"" + token(validClaims()).serialize() + "\"}";
            respondJson(exchange, response);
        } catch (JOSEException exception) {
            throw new IOException("could not sign test token", exception);
        }
    }

    private void respondToJwkRequest(HttpExchange exchange) throws IOException {
        jwkBuilderMarker.set(exchange.getRequestHeaders().getFirst(JWK_BUILDER_MARKER));
        respondJson(exchange, new JWKSet(signingKey.toPublicJWK()).toString());
    }

    private void respondJson(HttpExchange exchange, String response) throws IOException {
        byte[] body = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (java.io.OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
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
        SignedJWT token = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims);
        token.sign(new RSASSASigner(signingKey.toPrivateKey()));
        return token;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class BuilderMarkerConfiguration {

        @Bean
        RestClientCustomizer tokenBuilderMarkerCustomizer() {
            return builder -> builder.requestInterceptor((request, body, execution) -> {
                request.getHeaders().set(TOKEN_BUILDER_MARKER, "present");
                return execution.execute(request, body);
            });
        }

        @Bean
        RestTemplateCustomizer jwkBuilderMarkerCustomizer() {
            return restTemplate -> restTemplate.getInterceptors().add((request, body, execution) -> {
                request.getHeaders().set(JWK_BUILDER_MARKER, "present");
                return execution.execute(request, body);
            });
        }
    }
}
