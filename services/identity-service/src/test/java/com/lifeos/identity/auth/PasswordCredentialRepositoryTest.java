package com.lifeos.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.QueryHints;

/**
 * Verifies that session-creation credential locking remains bounded.
 */
class PasswordCredentialRepositoryTest {

    @Test
    void credentialLockUsesFiniteOneSecondTimeout() throws NoSuchMethodException {
        Method method = PasswordCredentialRepository.class.getMethod(
                "findByAccountIdForUpdate", java.util.UUID.class);
        QueryHints hints = method.getAnnotation(QueryHints.class);

        assertThat(hints).isNotNull();
        assertThat(hints.value()).anySatisfy(hint -> {
            assertThat(hint.name()).isEqualTo("jakarta.persistence.lock.timeout");
            assertThat(hint.value()).isEqualTo("1000");
        });
    }
}
