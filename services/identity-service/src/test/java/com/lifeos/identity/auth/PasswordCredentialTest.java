package com.lifeos.identity.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifeos.identity.account.UserAccount;
import org.junit.jupiter.api.Test;

/**
 * Verifies password-credential lifecycle invariants.
 */
class PasswordCredentialTest {

    @Test
    void revokedCredentialCannotBeReactivatedByPasswordRotation() {
        PasswordCredential credential = new PasswordCredential(
                new UserAccount("ada@example.com", "Ada Lovelace"), "argon2-hash");
        credential.revoke();

        assertThatThrownBy(() -> credential.replaceEncodedPassword("replacement-hash"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Revoked credentials cannot be reactivated");
    }
}
