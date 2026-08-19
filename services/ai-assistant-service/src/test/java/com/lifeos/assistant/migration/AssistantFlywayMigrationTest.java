package com.lifeos.assistant.migration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Ensures the independent H2 migration creates metadata-only conversation and audit stores. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:assistant-service-migration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=assistant-migration-password",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration-h2",
    "ai-assistant.audit-hmac-secret=assistant-migration-audit-secret",
    "identity.workload-token=assistant-migration-workload-token"
})
class AssistantFlywayMigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void migratesOnlyAssistantOwnedConversationAndAuditMetadataTables() {
        Integer tables = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where table_name in "
                        + "('ASSISTANT_CONVERSATION', 'ASSISTANT_REQUEST_AUDIT_EVENT', "
                        + "'AI_AUDIT_HASH_OUTBOX_EVENT', 'AI_AUDIT_HASH_OUTBOX_DEAD_LETTER')",
                Integer.class);

        assertThat(tables).isEqualTo(4);
        Integer relayColumns = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.columns where table_name = "
                        + "'AI_AUDIT_HASH_OUTBOX_EVENT' and column_name in "
                        + "('ATTEMPT_COUNT', 'NEXT_ATTEMPT_AT', 'LEASE_TOKEN', 'LEASE_UNTIL', "
                        + "'DEAD_LETTERED_AT', 'LAST_FAILURE_CODE')",
                Integer.class);
        assertThat(relayColumns).isEqualTo(6);
    }
}
