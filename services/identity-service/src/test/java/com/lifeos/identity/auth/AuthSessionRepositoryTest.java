package com.lifeos.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifeos.identity.account.UserAccount;
import com.lifeos.identity.account.UserAccountRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AuthSessionRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");

    @Autowired
    private AuthSessionRepository sessionRepository;

    @Autowired
    private UserAccountRepository accountRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void touchLastUsedAtPreservesNewerTimestampAndReportsActiveRow() {
        UserAccount account = accountRepository.saveAndFlush(
                new UserAccount("session-repository@example.com", "Session Repository"));
        UUID sessionId = UUID.randomUUID();
        sessionRepository.saveAndFlush(new AuthSession(
                sessionId,
                account,
                SessionAuthenticationMethod.PASSWORD,
                TokenDigest.sha256("repository-test-token"),
                NOW,
                NOW.plusSeconds(300)));

        assertThat(sessionRepository.touchLastUsedAt(sessionId, NOW.plusSeconds(10))).isEqualTo(1);
        assertThat(sessionRepository.touchLastUsedAt(sessionId, NOW)).isEqualTo(1);
        entityManager.clear();
        assertThat(sessionRepository.findById(sessionId)).get()
                .satisfies(session -> assertThat(session.getLastUsedAt()).isEqualTo(NOW.plusSeconds(10)));
    }
}
