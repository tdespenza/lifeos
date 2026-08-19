package com.lifeos.notification.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Executes the production PostgreSQL migration when Docker is available. Testcontainers skips this
 * class without Docker; the H2 migration test remains mandatory in restricted developer runners.
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "lifeos.postgres.migration.test", matches = "true|TRUE")
class NotificationPostgresFlywayIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("notification")
            .withUsername("notification")
            .withPassword("notification");

    @Test
    void migratesTheProductionNotificationSchema() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (var connection = POSTGRES.createConnection("");
                var statement = connection.prepareStatement(
                        "select count(*) from information_schema.tables where table_name = 'notification_outbox_event'")) {
            try (var rows = statement.executeQuery()) {
                rows.next();
                assertEquals(1, rows.getInt(1));
            }
        }
    }
}
