package com.lifeos.calendar.migration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** H2 mirror migration and Hibernate validation coverage required in Docker-restricted runners. */
@SpringBootTest
@ActiveProfiles("test")
class CalendarFlywayMigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createsTheCalendarEventReminderOutboxAndAuditTables() {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where table_name in "
                        + "('calendar_event', 'calendar_event_reminder', 'calendar_reminder', "
                        + "'calendar_outbox_event', 'calendar_mutation_idempotency', 'calendar_security_audit_event')",
                Integer.class);

        assertThat(count).isEqualTo(6);
    }
}
