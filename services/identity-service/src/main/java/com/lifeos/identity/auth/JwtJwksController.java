package com.lifeos.identity.auth;

import com.nimbusds.jose.jwk.JWKSet;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public verification-key endpoint for asymmetric JWT consumers. */
@RestController
public class JwtJwksController {

    private final JwtSigningMaterial material;

    public JwtJwksController(JwtSigningMaterial material) {
        this.material = material;
    }

    @GetMapping({"/.well-known/jwks.json", "/api/v1/auth/jwks"})
    public Map<String, Object> jwks() {
        JWKSet keySet = material.publicJwkSet();
        return keySet.toJSONObject();
    }
}
