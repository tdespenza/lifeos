package com.lifeos.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Tests the per-instance bound around memory-hard password verification.
 */
class PasswordVerifierTest {

    @Test
    void delegatesVerificationWhenCapacityIsAvailable() {
        PasswordEncoder encoder = Mockito.mock(PasswordEncoder.class);
        when(encoder.matches("secret", "encoded")).thenReturn(true);
        IdentityAuthProperties properties = new IdentityAuthProperties();
        PasswordVerifier verifier = new PasswordVerifier(encoder, properties);

        assertThat(verifier.matches("secret", "encoded")).isTrue();
    }

    @Test
    void delegatesEnrollmentHashingWhenCapacityIsAvailable() {
        PasswordEncoder encoder = Mockito.mock(PasswordEncoder.class);
        when(encoder.encode("enrollment-secret")).thenReturn("argon2id-encoded");
        PasswordVerifier verifier = new PasswordVerifier(encoder, new IdentityAuthProperties());

        assertThat(verifier.encode("enrollment-secret")).isEqualTo("argon2id-encoded");
    }

    @Test
    void failsClosedWhenAllVerificationPermitsAreBusy() throws Exception {
        PasswordEncoder encoder = Mockito.mock(PasswordEncoder.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(encoder.matches(anyString(), anyString())).thenAnswer(invocation -> {
            entered.countDown();
            release.await();
            return true;
        });

        IdentityAuthProperties properties = new IdentityAuthProperties();
        properties.getPassword().setMaxConcurrentVerifications(1);
        properties.getPassword().setVerificationAcquireTimeout(Duration.ofMillis(10));
        PasswordVerifier verifier = new PasswordVerifier(encoder, properties);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> first = executor.submit(() -> verifier.matches("one", "encoded"));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> verifier.matches("two", "encoded"))
                    .isInstanceOf(AuthenticationDependencyUnavailableException.class);

            release.countDown();
            assertThat(first.get(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}
