package com.lifeos.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthenticatedSubjectTest {

    @Test
    void fullyRedactsItsStringRepresentation() {
        UUID accountId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        String authenticationMethod = "PASSWORD";
        String accessTokenProof = "opaque-token-proof";

        String representation = new AuthenticatedSubject(
                accountId, sessionId, authenticationMethod, accessTokenProof).toString();

        assertThat(representation).isEqualTo("AuthenticatedSubject[redacted]");
        assertThat(representation)
                .doesNotContain(accountId.toString(), sessionId.toString(), authenticationMethod, accessTokenProof)
                .doesNotContain("accountId", "sessionId", "authenticationMethod", "accessTokenProof");
    }
}
