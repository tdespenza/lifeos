package com.lifeos.labs.performance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;

/**
 * Runs one bounded, read-only PostgreSQL query-plan probe against the identity schema.
 *
 * <p>The probe is deliberately opt-in: it requires a caller-supplied JDBC URL and credentials,
 * applies a ten-second statement timeout, and never creates or mutates data. It reports the plan
 * as JSON so a dated benchmark note can preserve the exact planner observation without inventing
 * a throughput claim.
 */
public final class PostgresQueryPlanMain {

    static final String QUERY = "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) "
            + "SELECT id FROM user_account WHERE email = ?";
    static final int QUERY_TIMEOUT_SECONDS = 10;

    private PostgresQueryPlanMain() {}

    public static void main(String[] args) throws SQLException {
        Configuration configuration = Configuration.fromEnvironment(System.getenv());
        try (Connection connection = DriverManager.getConnection(
                configuration.jdbcUrl(), configuration.username(), configuration.password())) {
            connection.setReadOnly(true);
            connection.setAutoCommit(true);
            try (PreparedStatement statement = connection.prepareStatement(QUERY)) {
                statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                statement.setString(1, configuration.email());
                Instant started = Instant.now();
                String plan;
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        throw new SQLException("PostgreSQL did not return an EXPLAIN plan");
                    }
                    plan = result.getString(1);
                }
                long elapsedMillis = Math.max(0L, Duration.between(started, Instant.now()).toMillis());
                boolean indexBacked = plan.contains("\"Index Scan\"")
                        || plan.contains("\"Index Only Scan\"")
                        || plan.contains("\"Bitmap Index Scan\"");
                System.out.println("{\"workload\":\"postgres-user-email-plan\",\"queryTimeoutSeconds\":"
                        + QUERY_TIMEOUT_SECONDS + ",\"elapsedMillis\":" + elapsedMillis
                        + ",\"indexBacked\":" + indexBacked + ",\"plan\":" + quoteJson(plan) + "}");
            }
        }
    }

    static record Configuration(String jdbcUrl, String username, String password, String email) {

        static Configuration fromEnvironment(java.util.Map<String, String> environment) {
            String jdbcUrl = required(environment, "LIFEOS_BENCHMARK_JDBC_URL");
            if (!jdbcUrl.startsWith("jdbc:postgresql://")) {
                throw new IllegalArgumentException("LIFEOS_BENCHMARK_JDBC_URL must use PostgreSQL JDBC");
            }
            String username = required(environment, "LIFEOS_BENCHMARK_JDBC_USERNAME");
            String password = required(environment, "LIFEOS_BENCHMARK_JDBC_PASSWORD");
            String email = environment.getOrDefault("LIFEOS_BENCHMARK_ACCOUNT_EMAIL", "benchmark@example.test");
            if (email.isBlank() || email.length() > 255 || email.indexOf('\n') >= 0 || email.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("LIFEOS_BENCHMARK_ACCOUNT_EMAIL must be a bounded value");
            }
            return new Configuration(jdbcUrl, username, password, email);
        }

        private static String required(java.util.Map<String, String> environment, String name) {
            String value = environment.get(name);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " is required for the opt-in query-plan probe");
            }
            return value;
        }
    }

    private static String quoteJson(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }
}
