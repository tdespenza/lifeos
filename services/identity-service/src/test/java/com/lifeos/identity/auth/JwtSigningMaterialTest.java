package com.lifeos.identity.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** Verifies that configured JWT signing key material is internally consistent. */
class JwtSigningMaterialTest {

    @Test
    void rejectsMismatchedRsaPrivateAndPublicKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair privateKeyPair = generator.generateKeyPair();
        KeyPair publicKeyPair = generator.generateKeyPair();
        IdentityAuthProperties properties = new IdentityAuthProperties();
        properties.getJwt().setPrivateKeyPem(
                pem("PRIVATE KEY", privateKeyPair.getPrivate().getEncoded()));
        properties.getJwt().setPublicKeyPem(
                pem("PUBLIC KEY", publicKeyPair.getPublic().getEncoded()));

        assertThatThrownBy(() -> JwtSigningMaterial.from(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Configured JWT RSA private and public keys do not match");
    }

    @Test
    void rejectsSameModulusWithDifferentPublicExponent() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        java.security.interfaces.RSAPublicKey originalPublicKey =
                (java.security.interfaces.RSAPublicKey) keyPair.getPublic();
        java.security.interfaces.RSAPublicKey mismatchedPublicKey =
                (java.security.interfaces.RSAPublicKey) KeyFactory.getInstance("RSA")
                        .generatePublic(new RSAPublicKeySpec(
                                originalPublicKey.getModulus(), BigInteger.valueOf(3)));
        IdentityAuthProperties properties = new IdentityAuthProperties();
        properties.getJwt().setPrivateKeyPem(
                pem("PRIVATE KEY", keyPair.getPrivate().getEncoded()));
        properties.getJwt().setPublicKeyPem(
                pem("PUBLIC KEY", mismatchedPublicKey.getEncoded()));

        assertThatThrownBy(() -> JwtSigningMaterial.from(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "Configured JWT RSA private and public keys do not form a valid signing pair");
    }

    private static String pem(String label, byte[] encoded) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(encoded);
        return "-----BEGIN " + label + "-----\n"
                + body
                + "\n-----END " + label + "-----";
    }
}
