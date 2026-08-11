package com.lifeos.identity.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.net.http.HttpClient;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.client.ClientHttpRequestFactory;

/**
 * Nimbus/Spring implementation of the OIDC authorization-code exchange.
 */
@Component
public class RestClientOidcProviderClient implements OidcProviderClient {

    private final RestClient restClient;
    private final RestOperations jwkRestOperations;
    private final ConcurrentMap<DecoderCacheKey, JwtDecoder> decoderCache = new ConcurrentHashMap<>();

    /**
     * Creates a provider client with a bounded, reusable HTTP client abstraction.
     */
    @Autowired
    public RestClientOidcProviderClient(IdentityAuthProperties properties) {
        this(buildRestClient(properties), buildJwkRestOperations(properties));
    }

    /**
     * Creates a provider client with an injectable HTTP client for tests.
     *
     * @param restClient HTTP client
     */
    RestClientOidcProviderClient(RestClient restClient) {
        this(restClient, new RestTemplate());
    }

    /**
     * Creates a provider client with injectable token and JWKS transports.
     *
     * @param restClient token-exchange client
     * @param jwkRestOperations JWKS retrieval client
     */
    RestClientOidcProviderClient(RestClient restClient, RestOperations jwkRestOperations) {
        this.restClient = restClient;
        this.jwkRestOperations = jwkRestOperations;
    }

    private static RestClient buildRestClient(IdentityAuthProperties properties) {
        return RestClient.builder().requestFactory(buildRequestFactory(properties)).build();
    }

    private static RestOperations buildJwkRestOperations(IdentityAuthProperties properties) {
        return new RestTemplate(buildRequestFactory(properties));
    }

    private static ClientHttpRequestFactory buildRequestFactory(IdentityAuthProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getOidc().getProviderConnectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getOidc().getProviderReadTimeout());
        return requestFactory;
    }

    @Override
    public OidcIdentity exchangeAndValidate(
            IdentityAuthProperties.Provider provider, String code, String codeVerifier, String nonce) {
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "authorization_code");
            form.add("client_id", provider.getClientId());
            form.add("client_secret", provider.getClientSecret());
            form.add("code", code);
            form.add("redirect_uri", provider.getRedirectUri());
            form.add("code_verifier", codeVerifier);
            OidcTokenResponse tokenResponse = restClient.post()
                    .uri(provider.getTokenUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(OidcTokenResponse.class);
            if (tokenResponse == null || !StringUtils.hasText(tokenResponse.idToken())) {
                throw new OidcAuthenticationFailureException();
            }

            Jwt jwt = decoder(provider).decode(tokenResponse.idToken());
            validateClaims(jwt, provider, nonce);
            String subject = requiredClaim(jwt.getSubject());
            String email = requiredClaim(jwt.getClaimAsString("email"));
            Object emailVerified = jwt.getClaim("email_verified");
            if (!isEmailVerified(emailVerified)) {
                throw new OidcAuthenticationFailureException();
            }
            String displayName = jwt.getClaimAsString("name");
            return new OidcIdentity(subject, email, StringUtils.hasText(displayName) ? displayName : email);
        } catch (OidcAuthenticationFailureException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().is5xxServerError()) {
                throw new AuthenticationDependencyUnavailableException();
            }
            // Do not retain or expose provider response bodies, which may contain secrets.
            throw new OidcAuthenticationFailureException();
        } catch (RestClientException exception) {
            throw new AuthenticationDependencyUnavailableException(exception);
        } catch (RuntimeException exception) {
            throw new OidcAuthenticationFailureException(exception);
        }
    }

    private JwtDecoder decoder(IdentityAuthProperties.Provider provider) {
        DecoderCacheKey cacheKey = new DecoderCacheKey(provider.getIssuer(), provider.getJwkSetUri());
        return decoderCache.computeIfAbsent(cacheKey, ignored -> {
            NimbusJwtDecoder jwtDecoder = NimbusJwtDecoder.withJwkSetUri(provider.getJwkSetUri())
                    .restOperations(jwkRestOperations)
                    .build();
            jwtDecoder.setJwtValidator(new OidcClaimsValidator(provider));
            return jwtDecoder;
        });
    }

    private void validateClaims(Jwt jwt, IdentityAuthProperties.Provider provider, String nonce) {
        java.net.URL issuer = jwt.getIssuer();
        if (issuer == null
                || !provider.getIssuer().equals(issuer.toString())
                || !jwt.getAudience().contains(provider.getClientId())
                || !nonce.equals(jwt.getClaimAsString("nonce"))) {
            throw new OidcAuthenticationFailureException();
        }
    }

    private String requiredClaim(String value) {
        if (!StringUtils.hasText(value)) {
            throw new OidcAuthenticationFailureException();
        }
        return value;
    }

    private boolean isEmailVerified(Object claim) {
        return Boolean.TRUE.equals(claim)
                || claim instanceof String value && "true".equalsIgnoreCase(value);
    }

    private record DecoderCacheKey(String issuer, String jwkSetUri) {
    }

    private static final class OidcClaimsValidator implements OAuth2TokenValidator<Jwt> {

        private final OAuth2TokenValidator<Jwt> timeAndIssuerValidator;

        private OidcClaimsValidator(IdentityAuthProperties.Provider provider) {
            this.timeAndIssuerValidator = JwtValidators.createDefaultWithIssuer(provider.getIssuer());
        }

        @Override
        public OAuth2TokenValidatorResult validate(Jwt token) {
            OAuth2TokenValidatorResult result = timeAndIssuerValidator.validate(token);
            if (result.hasErrors()) {
                return result;
            }
            return OAuth2TokenValidatorResult.success();
        }
    }

    /**
     * OIDC token response. Only the ID token is read by the identity service; access and refresh
     * tokens are intentionally not exposed by this package's public identity contract.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OidcTokenResponse(@JsonProperty("id_token") String idToken) {
    }
}
