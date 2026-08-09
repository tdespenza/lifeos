package com.lifeos.identity.auth;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Performs bounded password-hash verification.
 *
 * <p>Argon2id is intentionally memory-hard. A semaphore prevents a distributed or rotating
 * source of login attempts from allocating unbounded hashing memory inside one service instance.
 * Requests that cannot acquire a permit within the configured timeout fail closed with the same
 * temporary dependency error used for other unavailable authentication infrastructure.
 */
@Component
public class PasswordVerifier {

    private final PasswordEncoder passwordEncoder;
    private final Semaphore permits;
    private final Duration acquisitionTimeout;

    /**
     * Creates a bounded password verifier.
     *
     * @param passwordEncoder Argon2id password encoder
     * @param properties authentication properties
     */
    public PasswordVerifier(PasswordEncoder passwordEncoder, IdentityAuthProperties properties) {
        this.passwordEncoder = passwordEncoder;
        IdentityAuthProperties.Password password = properties.getPassword();
        if (password.getMaxConcurrentVerifications() < 1) {
            throw new IllegalArgumentException("maxConcurrentVerifications must be positive");
        }
        if (password.getVerificationAcquireTimeout().isNegative()
                || password.getVerificationAcquireTimeout().isZero()) {
            throw new IllegalArgumentException("verificationAcquireTimeout must be positive");
        }
        this.permits = new Semaphore(password.getMaxConcurrentVerifications(), true);
        this.acquisitionTimeout = password.getVerificationAcquireTimeout();
    }

    /**
     * Verifies a raw password against an encoded password while holding one bounded permit.
     *
     * @param rawPassword submitted password
     * @param encodedPassword stored Argon2id hash
     * @return whether the password matches
     * @throws AuthenticationDependencyUnavailableException when local hashing capacity is full
     */
    public boolean matches(String rawPassword, String encodedPassword) {
        boolean acquired;
        try {
            acquired = permits.tryAcquire(1, acquisitionTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AuthenticationDependencyUnavailableException(exception);
        }
        if (!acquired) {
            throw new AuthenticationDependencyUnavailableException();
        }
        try {
            try {
                return passwordEncoder.matches(rawPassword, encodedPassword);
            } catch (RuntimeException exception) {
                throw new AuthenticationDependencyUnavailableException(exception);
            }
        } finally {
            permits.release();
        }
    }
}
