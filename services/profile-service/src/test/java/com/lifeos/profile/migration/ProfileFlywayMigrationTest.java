package com.lifeos.profile.migration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Ensures the explicit H2 migration path creates the production-owned tables used by tests. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:profile-migration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=profile-migration-password",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration-h2",
    "profile.idempotency-secret=migration-idempotency-secret",
    "profile.audit-client-fingerprint-secret=migration-audit-secret",
    "identity.workload-token=migration-workload-token"
})
class ProfileFlywayMigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void migratesProfileSettingsHouseholdAndIdempotencyTables() {
        Integer tables = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where table_name in "
                        + "('PERSONAL_PROFILE', 'PROFILE_PREFERENCES', 'HOUSEHOLD', 'PROFILE_MUTATION_IDEMPOTENCY')",
                Integer.class);

        assertThat(tables).isEqualTo(4);
    }
}
