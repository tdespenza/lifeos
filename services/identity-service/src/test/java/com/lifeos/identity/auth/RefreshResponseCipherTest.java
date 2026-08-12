package com.lifeos.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class RefreshResponseCipherTest {

    @Test
    void encryptsAndRoundTripsResponseWithoutPersistingRawCredentials() {
        IdentityAuthProperties properties = new IdentityAuthProperties();
        properties.getJwt().setSigningSecret("test-only-secret-that-is-at-least-32-bytes-long");
        properties.getJwt().setReplayEncryptionSecret("test-only-refresh-replay-secret-at-least-32-bytes");
        RefreshResponseCipher cipher = new RefreshResponseCipher(
                properties, JwtSigningMaterial.from(properties));
        LoginResponse original = new LoginResponse(
                UUID.randomUUID(), "signed-access-token", "Bearer", 300,
                "opaque-refresh-token", 2_592_000);

        UUID familyId = UUID.randomUUID();
        String envelope = cipher.encrypt(familyId, "idempotency-key", original);

        assertThat(envelope).doesNotContain(original.accessToken(), original.refreshToken());
        assertThat(cipher.decrypt(familyId, "idempotency-key", envelope)).isEqualTo(original);
    }

    @Test
    void rejectsEnvelopeWhenReplayRecordIdentityChanges() {
        IdentityAuthProperties properties = new IdentityAuthProperties();
        properties.getJwt().setSigningSecret("test-only-secret-that-is-at-least-32-bytes-long");
        properties.getJwt().setReplayEncryptionSecret("test-only-refresh-replay-secret-at-least-32-bytes");
        RefreshResponseCipher cipher = new RefreshResponseCipher(
                properties, JwtSigningMaterial.from(properties));
        LoginResponse original = new LoginResponse(
                UUID.randomUUID(), "signed-access-token", "Bearer", 300,
                "opaque-refresh-token", 2_592_000);
        UUID familyId = UUID.randomUUID();
        String envelope = cipher.encrypt(familyId, "idempotency-key", original);

        assertThatThrownBy(() -> cipher.decrypt(familyId, "other-key", envelope))
                .isInstanceOf(AuthenticationFailureException.class);
    }

    @Test
    void rejectsMissingEnvelope() {
        IdentityAuthProperties properties = new IdentityAuthProperties();
        properties.getJwt().setSigningSecret("test-only-secret-that-is-at-least-32-bytes-long");
        properties.getJwt().setReplayEncryptionSecret("test-only-refresh-replay-secret-at-least-32-bytes");
        RefreshResponseCipher cipher = new RefreshResponseCipher(
                properties, JwtSigningMaterial.from(properties));

        assertThatThrownBy(() -> cipher.decrypt(UUID.randomUUID(), "idempotency-key", null))
                .isInstanceOf(AuthenticationFailureException.class);
    }
}
