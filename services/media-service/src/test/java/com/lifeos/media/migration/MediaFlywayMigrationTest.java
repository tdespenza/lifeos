package com.lifeos.media.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** H2 mirror migration and Hibernate validation coverage for Media's independent store. */
@SpringBootTest
@ActiveProfiles("test")
class MediaFlywayMigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createsMediaTablesIncludingPostSessionArtifacts() {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where table_name in "
                        + "('media_asset', 'media_session', 'media_mutation_idempotency', "
                        + "'media_security_audit_event', 'media_session_artifact')",
                Integer.class);

        assertThat(count).isEqualTo(5);
    }

    @Test
    void sessionStatusConstraintMatchesTheJavaEndedLifecycleState() {
        UUID sessionId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Instant start = Instant.parse("2030-01-01T10:00:00Z");
        Instant end = Instant.parse("2030-01-01T11:00:00Z");

        jdbcTemplate.update(
                "insert into media_session (id, owner_account_id, tenant_id, kind, title, "
                        + "scheduled_start_at, scheduled_end_at, time_zone, status, created_at, updated_at, version) "
                        + "values (?, ?, ?, 'COACHING', ?, ?, ?, 'UTC', 'ENDED', ?, ?, 0)",
                sessionId,
                ownerId,
                ownerId.toString(),
                "Ended session",
                Timestamp.from(start),
                Timestamp.from(end),
                Timestamp.from(start),
                Timestamp.from(end));

        Integer ended = jdbcTemplate.queryForObject(
                "select count(*) from media_session where id = ? and status = 'ENDED'", Integer.class, sessionId);
        assertThat(ended).isEqualTo(1);
    }
}
