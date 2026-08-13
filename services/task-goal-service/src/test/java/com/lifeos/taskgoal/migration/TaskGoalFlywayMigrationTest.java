package com.lifeos.taskgoal.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/** Verifies the supported one-time baseline path for a pre-owner-scoped goal schema. */
class TaskGoalFlywayMigrationTest {

    private static final String H2_MIGRATION_LOCATION = "classpath:db/migration-h2";

    @Test
    void appliesV2AndV3ToAnExistingV1BaselinedSchemaWithoutInventingOwnership() throws Exception {
        String databaseUrl = "jdbc:h2:mem:task-goal-flyway-v2;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        UUID legacyGoalId = UUID.randomUUID();
        try (Connection connection = DriverManager.getConnection(databaseUrl, "sa", "");
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE goal (
                        id UUID NOT NULL PRIMARY KEY,
                        title VARCHAR(255) NOT NULL,
                        created_at TIMESTAMP WITH TIME ZONE NOT NULL
                    )
                    """);
            try (var insert = connection.prepareStatement("""
                    INSERT INTO goal (id, title, created_at)
                    VALUES (?, ?, CURRENT_TIMESTAMP)
                    """)) {
                insert.setObject(1, legacyGoalId);
                insert.setString(2, "Legacy goal without authorization scope");
                insert.executeUpdate();
            }
        }

        Flyway.configure()
                .dataSource(databaseUrl, "sa", "")
                // H2 cannot parse PostgreSQL's CREATE INDEX CONCURRENTLY in the production V3.
                .locations(H2_MIGRATION_LOCATION)
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(databaseUrl, "sa", "")) {
            assertThat(columnExists(connection, "GOAL", "OWNER_ACCOUNT_ID")).isTrue();
            assertThat(columnExists(connection, "GOAL", "TENANT_ID")).isTrue();
            assertThat(indexExists(connection, "GOAL", "IDX_GOAL_OWNER_TENANT")).isTrue();
            try (var legacyGoal = connection.prepareStatement("""
                    SELECT owner_account_id, tenant_id
                    FROM goal
                    WHERE id = ?
                    """)) {
                legacyGoal.setObject(1, legacyGoalId);
                try (ResultSet result = legacyGoal.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getObject("OWNER_ACCOUNT_ID")).isNull();
                    assertThat(result.getString("TENANT_ID")).isNull();
                }
            }
        }
    }

    private boolean columnExists(Connection connection, String tableName, String columnName) throws Exception {
        try (ResultSet columns = connection.getMetaData().getColumns(null, null, tableName, columnName)) {
            return columns.next() && "YES".equals(columns.getString("IS_NULLABLE"));
        }
    }

    private boolean indexExists(Connection connection, String tableName, String indexName) throws Exception {
        try (ResultSet indexes = connection.getMetaData().getIndexInfo(null, null, tableName, false, false)) {
            while (indexes.next()) {
                if (indexName.equalsIgnoreCase(indexes.getString("INDEX_NAME"))) {
                    return true;
                }
            }
            return false;
        }
    }
}
