package com.lifeos.notification.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Executes the H2-specific migration scripts used by fast local/integration verification. */
class NotificationH2FlywayMigrationTest {

    @Test
    void migratesTheCompleteNotificationFoundationOnH2() throws Exception {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:notification-migration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration-h2").load().migrate();

        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(
                        "select count(*) from information_schema.tables where table_name = 'notification_outbox_event'")) {
            try (var rows = statement.executeQuery()) {
                rows.next();
                assertEquals(1, rows.getInt(1));
            }
        }
    }
}
