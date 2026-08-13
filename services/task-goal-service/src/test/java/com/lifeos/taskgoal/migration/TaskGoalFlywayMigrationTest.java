package com.lifeos.taskgoal.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/** Verifies the supported one-time baseline path for a pre-owner-scoped goal schema. */
class TaskGoalFlywayMigrationTest {

    @Test
    void appliesV2ToAnExistingV1BaselinedSchemaWithoutInventingOwnership() throws Exception {
        String databaseUrl = "jdbc:h2:mem:task-goal-flyway-v2;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(databaseUrl, "sa", "");
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE goal (
                        id UUID NOT NULL PRIMARY KEY,
                        title VARCHAR(255) NOT NULL,
                        created_at TIMESTAMP WITH TIME ZONE NOT NULL
                    )
                    """);
        }

        Flyway.configure()
                .dataSource(databaseUrl, "sa", "")
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(databaseUrl, "sa", "")) {
            assertThat(columnExists(connection, "GOAL", "OWNER_ACCOUNT_ID")).isTrue();
            assertThat(columnExists(connection, "GOAL", "TENANT_ID")).isTrue();
            assertThat(indexExists(connection, "GOAL", "IDX_GOAL_OWNER_TENANT")).isTrue();
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
