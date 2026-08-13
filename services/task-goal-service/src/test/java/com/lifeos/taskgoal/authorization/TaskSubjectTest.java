package com.lifeos.taskgoal.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskSubjectTest {

    private static final String ACCESS_TOKEN_PROOF =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Test
    void fullyRedactsItsStringRepresentation() {
        UUID accountId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        String authenticationMethod = "password";

        String representation = new TaskSubject(
                accountId, sessionId, authenticationMethod, ACCESS_TOKEN_PROOF).toString();

        assertThat(representation).isEqualTo("TaskSubject[redacted]");
        assertThat(representation)
                .doesNotContain(accountId.toString(), sessionId.toString(), authenticationMethod, ACCESS_TOKEN_PROOF)
                .doesNotContain("accountId", "sessionId", "authenticationMethod", "accessTokenProof");
    }
}
