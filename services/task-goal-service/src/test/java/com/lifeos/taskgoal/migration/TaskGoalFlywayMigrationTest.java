package com.lifeos.taskgoal.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void appliesV2ThroughV6ToAnExistingV1BaselinedSchemaWithoutInventingOwnership() throws Exception {
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
            assertThat(tableExists(connection, "GOAL_CREATE_IDEMPOTENCY")).isTrue();
            assertThat(tableExists(connection, "GOAL_MUTATION_IDEMPOTENCY")).isTrue();
            assertThat(tableExists(connection, "TASK")).isTrue();
            assertThat(tableExists(connection, "TASK_COMMAND_IDEMPOTENCY")).isTrue();
            assertThat(tableExists(connection, "TASK_GOAL_DEPENDENCY")).isTrue();
            assertThat(tableExists(connection, "TASK_GOAL_DEPENDENCY_GUARD")).isTrue();
            assertThat(indexExists(connection, "GOAL_CREATE_IDEMPOTENCY", "IDX_GOAL_CREATE_IDEMPOTENCY_GOAL"))
                    .isTrue();
            assertThat(indexExists(connection, "GOAL_MUTATION_IDEMPOTENCY", "IDX_GOAL_MUTATION_IDEMPOTENCY_GOAL"))
                    .isTrue();
            assertThat(columnPresent(connection, "GOAL", "UPDATED_AT")).isTrue();
            assertThat(columnPresent(connection, "GOAL", "STATUS")).isTrue();
            assertThat(columnPresent(connection, "GOAL", "VERSION")).isTrue();
            assertScopeBoundKeyIsUnique(connection);
            assertMutationScopeBoundKeyIsUnique(connection);
            assertTaskCommandScopeBoundKeyIsUnique(connection);
            try (var legacyGoal = connection.prepareStatement("""
                    SELECT owner_account_id, tenant_id, status, version, updated_at
                    FROM goal
                    WHERE id = ?
                    """)) {
                legacyGoal.setObject(1, legacyGoalId);
                try (ResultSet result = legacyGoal.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getObject("OWNER_ACCOUNT_ID")).isNull();
                    assertThat(result.getString("TENANT_ID")).isNull();
                    assertThat(result.getString("STATUS")).isEqualTo("ACTIVE");
                    assertThat(result.getLong("VERSION")).isZero();
                    assertThat(result.getObject("UPDATED_AT")).isNotNull();
                }
            }
            try (Statement statement = connection.createStatement()) {
                assertThatThrownBy(() -> statement.executeUpdate("""
                        UPDATE goal
                        SET status = 'COMPLETED'
                        WHERE id = '%s'
                        """.formatted(legacyGoalId)))
                        .isInstanceOf(java.sql.SQLException.class);
            }
        }
    }

    @Test
    void appliesV5AfterAnExistingV4IdempotencyBaselineWithoutChangingCreateReservations() throws Exception {
        String databaseUrl = "jdbc:h2:mem:task-goal-flyway-v5;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        UUID goalId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID ownerAccountId = UUID.randomUUID();
        try (Connection connection = DriverManager.getConnection(databaseUrl, "sa", "");
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE goal (
                        id UUID NOT NULL PRIMARY KEY,
                        title VARCHAR(255) NOT NULL,
                        created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                        owner_account_id UUID,
                        tenant_id VARCHAR(255)
                    )
                    """);
            statement.execute("""
                    CREATE INDEX idx_goal_owner_tenant
                    ON goal (owner_account_id, tenant_id)
                    """);
            statement.execute("""
                    CREATE TABLE goal_create_idempotency (
                        id UUID NOT NULL,
                        owner_account_id UUID NOT NULL,
                        tenant_id VARCHAR(255) NOT NULL,
                        idempotency_key_hash VARCHAR(64) NOT NULL,
                        request_fingerprint VARCHAR(64) NOT NULL,
                        goal_id UUID NOT NULL,
                        state VARCHAR(16) NOT NULL,
                        created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                        completed_at TIMESTAMP WITH TIME ZONE,
                        CONSTRAINT pk_goal_create_idempotency PRIMARY KEY (id),
                        CONSTRAINT uk_goal_create_idempotency_scope_key
                            UNIQUE (owner_account_id, tenant_id, idempotency_key_hash)
                    )
                    """);
            statement.execute("""
                    CREATE INDEX idx_goal_create_idempotency_goal
                    ON goal_create_idempotency (goal_id)
                    """);
            try (var goalInsert = connection.prepareStatement("""
                    INSERT INTO goal (id, title, created_at, owner_account_id, tenant_id)
                    VALUES (?, ?, CURRENT_TIMESTAMP, ?, ?)
                    """)) {
                goalInsert.setObject(1, goalId);
                goalInsert.setString(2, "Existing V4 goal");
                goalInsert.setObject(3, ownerAccountId);
                goalInsert.setString(4, ownerAccountId.toString());
                goalInsert.executeUpdate();
            }
            try (var reservationInsert = connection.prepareStatement("""
                    INSERT INTO goal_create_idempotency (
                        id, owner_account_id, tenant_id, idempotency_key_hash, request_fingerprint,
                        goal_id, state, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, 'COMPLETED', CURRENT_TIMESTAMP)
                    """)) {
                reservationInsert.setObject(1, reservationId);
                reservationInsert.setObject(2, ownerAccountId);
                reservationInsert.setString(3, ownerAccountId.toString());
                reservationInsert.setString(4, "a".repeat(64));
                reservationInsert.setString(5, "b".repeat(64));
                reservationInsert.setObject(6, goalId);
                reservationInsert.executeUpdate();
            }
        }

        Flyway.configure()
                .dataSource(databaseUrl, "sa", "")
                .locations(H2_MIGRATION_LOCATION)
                .baselineOnMigrate(true)
                .baselineVersion("4")
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(databaseUrl, "sa", "")) {
            assertThat(tableExists(connection, "GOAL_MUTATION_IDEMPOTENCY")).isTrue();
            assertThat(columnPresent(connection, "GOAL", "UPDATED_AT")).isTrue();
            assertThat(columnPresent(connection, "GOAL", "STATUS")).isTrue();
            assertThat(columnPresent(connection, "GOAL", "VERSION")).isTrue();
            try (var goal = connection.prepareStatement("""
                    SELECT status, version
                    FROM goal
                    WHERE id = ?
                    """)) {
                goal.setObject(1, goalId);
                try (ResultSet result = goal.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString("STATUS")).isEqualTo("ACTIVE");
                    assertThat(result.getLong("VERSION")).isZero();
                }
            }
            try (var reservation = connection.prepareStatement("""
                    SELECT COUNT(*)
                    FROM goal_create_idempotency
                    WHERE id = ? AND state = 'COMPLETED'
                    """)) {
                reservation.setObject(1, reservationId);
                try (ResultSet result = reservation.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getLong(1)).isEqualTo(1L);
                }
            }
        }
    }

    @Test
    void appliesV6AfterAnExistingV5GoalLifecycleBaseline() throws Exception {
        String databaseUrl = "jdbc:h2:mem:task-goal-flyway-v6;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(databaseUrl, "sa", "");
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE goal (
                        id UUID NOT NULL PRIMARY KEY,
                        title VARCHAR(255) NOT NULL,
                        created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                        owner_account_id UUID,
                        tenant_id VARCHAR(255),
                        updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                        status VARCHAR(16) NOT NULL,
                        completed_at TIMESTAMP WITH TIME ZONE,
                        archived_at TIMESTAMP WITH TIME ZONE,
                        version BIGINT NOT NULL
                    )
                    """);
            statement.execute("CREATE INDEX idx_goal_owner_tenant ON goal (owner_account_id, tenant_id)");
            // Baseline at V5 models a deployed lifecycle schema. V6 must add Task/graph state
            // without touching existing Goal rows or replay reservations.
        }

        Flyway.configure()
                .dataSource(databaseUrl, "sa", "")
                .locations(H2_MIGRATION_LOCATION)
                .baselineOnMigrate(true)
                .baselineVersion("5")
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(databaseUrl, "sa", "")) {
            assertThat(tableExists(connection, "TASK")).isTrue();
            assertThat(tableExists(connection, "TASK_COMMAND_IDEMPOTENCY")).isTrue();
            assertThat(tableExists(connection, "TASK_GOAL_DEPENDENCY")).isTrue();
            assertThat(indexExists(connection, "TASK", "IDX_TASK_OWNER_TENANT")).isTrue();
            assertThat(indexExists(connection, "TASK_COMMAND_IDEMPOTENCY", "IDX_TASK_COMMAND_IDEMPOTENCY_TASK"))
                    .isTrue();
            assertThat(indexExists(connection, "TASK_GOAL_DEPENDENCY", "IDX_TASK_GOAL_DEPENDENCY_SCOPE_PREDECESSOR"))
                    .isTrue();
        }
    }

    private boolean columnExists(Connection connection, String tableName, String columnName) throws Exception {
        try (ResultSet columns = connection.getMetaData().getColumns(null, null, tableName, columnName)) {
            return columns.next() && "YES".equals(columns.getString("IS_NULLABLE"));
        }
    }

    private boolean columnPresent(Connection connection, String tableName, String columnName) throws Exception {
        try (ResultSet columns = connection.getMetaData().getColumns(null, null, tableName, columnName)) {
            return columns.next();
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

    private boolean tableExists(Connection connection, String tableName) throws Exception {
        try (ResultSet tables = connection.getMetaData().getTables(null, null, tableName, new String[] {"TABLE"})) {
            return tables.next();
        }
    }

    private void assertScopeBoundKeyIsUnique(Connection connection) throws Exception {
        UUID ownerAccountId = UUID.randomUUID();
        UUID firstRecordId = UUID.randomUUID();
        try (var insert = connection.prepareStatement("""
                INSERT INTO goal_create_idempotency (
                    id, owner_account_id, tenant_id, idempotency_key_hash, request_fingerprint,
                    goal_id, state, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """)) {
            insert.setObject(1, firstRecordId);
            insert.setObject(2, ownerAccountId);
            insert.setString(3, ownerAccountId.toString());
            insert.setString(4, "a".repeat(64));
            insert.setString(5, "b".repeat(64));
            insert.setObject(6, UUID.randomUUID());
            insert.setString(7, "PENDING");
            insert.executeUpdate();

            insert.setObject(1, UUID.randomUUID());
            insert.setObject(6, UUID.randomUUID());
            assertThatThrownBy(insert::executeUpdate).isInstanceOf(java.sql.SQLException.class);
        }
    }

    private void assertMutationScopeBoundKeyIsUnique(Connection connection) throws Exception {
        UUID actorAccountId = UUID.randomUUID();
        UUID goalId = UUID.randomUUID();
        try (var insert = connection.prepareStatement("""
                INSERT INTO goal_mutation_idempotency (
                    id, actor_account_id, tenant_id, goal_id, operation, idempotency_key_hash,
                    request_fingerprint, expected_version, state, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """)) {
            insert.setObject(1, UUID.randomUUID());
            insert.setObject(2, actorAccountId);
            insert.setString(3, actorAccountId.toString());
            insert.setObject(4, goalId);
            insert.setString(5, "UPDATE");
            insert.setString(6, "a".repeat(64));
            insert.setString(7, "b".repeat(64));
            insert.setLong(8, 0L);
            insert.setString(9, "PENDING");
            insert.executeUpdate();

            insert.setObject(1, UUID.randomUUID());
            assertThatThrownBy(insert::executeUpdate).isInstanceOf(java.sql.SQLException.class);
        }

        try (var malformedSnapshot = connection.prepareStatement("""
                INSERT INTO goal_mutation_idempotency (
                    id, actor_account_id, tenant_id, goal_id, operation, idempotency_key_hash,
                    request_fingerprint, expected_version, state, result_title, result_status,
                    result_version, result_created_at, result_updated_at, result_archived_at,
                    created_at, completed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """)) {
            malformedSnapshot.setObject(1, UUID.randomUUID());
            malformedSnapshot.setObject(2, actorAccountId);
            malformedSnapshot.setString(3, actorAccountId.toString());
            malformedSnapshot.setObject(4, goalId);
            malformedSnapshot.setString(5, "UPDATE");
            malformedSnapshot.setString(6, "c".repeat(64));
            malformedSnapshot.setString(7, "d".repeat(64));
            malformedSnapshot.setLong(8, 0L);
            malformedSnapshot.setString(9, "COMPLETED");
            malformedSnapshot.setString(10, "Invalid active snapshot");
            malformedSnapshot.setString(11, "ACTIVE");
            malformedSnapshot.setLong(12, 1L);

            assertThatThrownBy(malformedSnapshot::executeUpdate).isInstanceOf(java.sql.SQLException.class);
        }
    }

    private void assertTaskCommandScopeBoundKeyIsUnique(Connection connection) throws Exception {
        UUID actorAccountId = UUID.randomUUID();
        try (var insert = connection.prepareStatement("""
                INSERT INTO task_command_idempotency (
                    id, actor_account_id, tenant_id, operation, target_scope, task_id,
                    idempotency_key_hash, request_fingerprint, state, created_at
                ) VALUES (?, ?, ?, 'CREATE', 'create', ?, ?, ?, 'PENDING', CURRENT_TIMESTAMP)
                """)) {
            insert.setObject(1, UUID.randomUUID());
            insert.setObject(2, actorAccountId);
            insert.setString(3, actorAccountId.toString());
            insert.setObject(4, UUID.randomUUID());
            insert.setString(5, "a".repeat(64));
            insert.setString(6, "b".repeat(64));
            insert.executeUpdate();

            insert.setObject(1, UUID.randomUUID());
            insert.setObject(4, UUID.randomUUID());
            assertThatThrownBy(insert::executeUpdate).isInstanceOf(java.sql.SQLException.class);
        }
    }
}
