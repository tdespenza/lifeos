package com.lifeos.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class RefreshResponseCipherTest {

    @Test
    void encryptsAndRoundTripsResponseWithoutPersistingRawCredentials() {
        IdentityAuthProperties properties = new IdentityAuthProperties();
        properties.getJwt().setSigningSecret("test-only-secret-that-is-at-least-32-bytes-long");
        RefreshResponseCipher cipher = new RefreshResponseCipher(
                properties, JwtSigningMaterial.from(properties));
        LoginResponse original = new LoginResponse(
                UUID.randomUUID(), "signed-access-token", "Bearer", 300,
                "opaque-refresh-token", 2_592_000);

        String envelope = cipher.encrypt(original);

        assertThat(envelope).doesNotContain(original.accessToken(), original.refreshToken());
        assertThat(cipher.decrypt(envelope)).isEqualTo(original);
    }
}
