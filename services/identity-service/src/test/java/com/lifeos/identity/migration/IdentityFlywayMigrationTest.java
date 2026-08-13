package com.lifeos.identity.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/** Verifies the supported one-time baseline path for a pre-Story-1.6 identity schema. */
class IdentityFlywayMigrationTest {

    private static final String H2_MIGRATION_LOCATION = "classpath:db/migration-h2";

    @Test
    void appliesV2ToAnExistingV1BaselinedSchema() throws Exception {
        String databaseUrl = h2Url("identity-flyway-v2");
        try (Connection connection = DriverManager.getConnection(databaseUrl, "sa", "");
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE user_account (
                        id UUID NOT NULL PRIMARY KEY,
                        email VARCHAR(255) NOT NULL,
                        display_name VARCHAR(255) NOT NULL,
                        created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                        status VARCHAR(16) NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE security_audit_event (
                        id UUID NOT NULL PRIMARY KEY,
                        event_type VARCHAR(64) NOT NULL,
                        account_id UUID,
                        correlation_id VARCHAR(128) NOT NULL,
                        client_fingerprint VARCHAR(64) NOT NULL,
                        occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
                    )
                    """);
        }

        Flyway.configure()
                .dataSource(databaseUrl, "sa", "")
                .locations("classpath:db/migration", H2_MIGRATION_LOCATION)
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(databaseUrl, "sa", "")) {
            assertThat(columnExists(connection, "SECURITY_AUDIT_EVENT", "OUTCOME_CODE")).isTrue();
            assertThat(tableExists(connection, "AUTHORIZATION_MEMBERSHIP")).isTrue();
            assertThat(uniqueConstraintExists(
                            connection,
                            "AUTHORIZATION_MEMBERSHIP",
                            "UK_AUTHORIZATION_MEMBERSHIP_ACCOUNT_TENANT_ROLE"))
                    .isTrue();
            assertThat(tableExists(connection, "flyway_schema_history")).isTrue();
        }
    }

    @Test
    void appliesFullBaselineWithH2EquivalentOfPostgresOidColumn() throws Exception {
        String databaseUrl = h2Url("identity-flyway-fresh");

        Flyway.configure()
                .dataSource(databaseUrl, "sa", "")
                .locations("classpath:db/migration", H2_MIGRATION_LOCATION)
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(databaseUrl, "sa", "")) {
            assertThat(columnExists(connection, "REFRESH_REPLAY_RECORD", "ENCRYPTED_RESPONSE"))
                    .isTrue();
            assertThat(tableExists(connection, "AUTHORIZATION_MEMBERSHIP")).isTrue();
            assertThat(columnExists(connection, "AUTH_SESSION", "LAST_USED_AT")).isTrue();
            assertThat(indexExists(connection, "AUTH_SESSION", "IX_AUTH_SESSION_ACCOUNT_CURSOR"))
                    .isTrue();
        }
    }

    private boolean columnExists(Connection connection, String tableName, String columnName) throws Exception {
        try (ResultSet columns = connection.getMetaData().getColumns(null, null, tableName, columnName)) {
            return columns.next();
        }
    }

    private String h2Url(String databaseName) {
        return "jdbc:h2:mem:" + databaseName
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS OID AS CLOB";
    }

    private boolean tableExists(Connection connection, String tableName) throws Exception {
        try (ResultSet tables = connection.getMetaData().getTables(null, null, null, null)) {
            while (tables.next()) {
                if (tableName.equalsIgnoreCase(tables.getString("TABLE_NAME"))) {
                    return true;
                }
            }
            return false;
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

    private boolean uniqueConstraintExists(Connection connection, String tableName, String constraintName)
            throws Exception {
        try (var statement = connection.prepareStatement("""
                SELECT 1
                FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
                WHERE TABLE_NAME = ?
                  AND CONSTRAINT_NAME = ?
                  AND CONSTRAINT_TYPE = 'UNIQUE'
                """)) {
            statement.setString(1, tableName);
            statement.setString(2, constraintName);
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }
}
