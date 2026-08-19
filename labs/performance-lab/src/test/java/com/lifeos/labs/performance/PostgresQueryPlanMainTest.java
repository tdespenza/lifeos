package com.lifeos.labs.performance;

import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresQueryPlanMainTest {

    @Test
    void requiresPostgresUrlAndNonBlankCredentials() {
        IllegalArgumentException missing = assertThrows(
                IllegalArgumentException.class,
                () -> PostgresQueryPlanMain.Configuration.fromEnvironment(Map.of()));
        assertTrue(missing.getMessage().contains("LIFEOS_BENCHMARK_JDBC_URL"));

        IllegalArgumentException wrongDriver = assertThrows(
                IllegalArgumentException.class,
                () -> PostgresQueryPlanMain.Configuration.fromEnvironment(Map.of(
                        "LIFEOS_BENCHMARK_JDBC_URL", "jdbc:h2:mem:test",
                        "LIFEOS_BENCHMARK_JDBC_USERNAME", "sa",
                        "LIFEOS_BENCHMARK_JDBC_PASSWORD", "secret")));
        assertTrue(wrongDriver.getMessage().contains("PostgreSQL JDBC"));
    }

    @Test
    void appliesSafeDefaultEmailAndRejectsUnboundedValues() {
        PostgresQueryPlanMain.Configuration configuration = PostgresQueryPlanMain.Configuration.fromEnvironment(
                Map.of(
                        "LIFEOS_BENCHMARK_JDBC_URL", "jdbc:postgresql://localhost:5432/lifeos_identity",
                        "LIFEOS_BENCHMARK_JDBC_USERNAME", "local",
                        "LIFEOS_BENCHMARK_JDBC_PASSWORD", "secret"));
        assertEquals("benchmark@example.test", configuration.email());

        IllegalArgumentException unbounded = assertThrows(
                IllegalArgumentException.class,
                () -> PostgresQueryPlanMain.Configuration.fromEnvironment(Map.of(
                        "LIFEOS_BENCHMARK_JDBC_URL", "jdbc:postgresql://localhost:5432/lifeos_identity",
                        "LIFEOS_BENCHMARK_JDBC_USERNAME", "local",
                        "LIFEOS_BENCHMARK_JDBC_PASSWORD", "secret",
                        "LIFEOS_BENCHMARK_ACCOUNT_EMAIL", "\n")));
        assertTrue(unbounded.getMessage().contains("ACCOUNT_EMAIL"));
    }
}
