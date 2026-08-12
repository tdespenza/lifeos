package com.lifeos.identity.auth;

import com.nimbusds.jose.jwk.JWKSet;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public verification-key endpoint for asymmetric JWT consumers. */
@RestController
public class JwtJwksController {

    private final JwtSigningMaterial material;

    /**
     * Creates the public-key discovery controller.
     *
     * @param material resolved signing material
     */
    public JwtJwksController(JwtSigningMaterial material) {
        this.material = material;
    }

    /**
     * Publishes public RSA verification keys without exposing symmetric secrets.
     *
     * @return cacheable JWKS response
     */
    @GetMapping({"/.well-known/jwks.json", "/api/v1/auth/jwks"})
    public ResponseEntity<Map<String, Object>> jwks() {
        JWKSet keySet = material.publicJwkSet();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(keySet.toJSONObject());
    }
}
