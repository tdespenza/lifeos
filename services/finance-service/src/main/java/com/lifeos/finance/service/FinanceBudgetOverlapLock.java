package com.lifeos.finance.service;

import java.sql.PreparedStatement;
import javax.sql.DataSource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Serializes overlapping budget writes for one owner/tenant/category on PostgreSQL.
 *
 * <p>PostgreSQL exclusion constraints are the final invariant, but two concurrent overlapping
 * inserts can deadlock while the exclusion index checks each other. A transaction-scoped advisory
 * lock turns that rare database deadlock into a deterministic precheck/constraint conflict. H2
 * and other local test databases do not expose the PostgreSQL function, so the lock is a no-op
 * there; the database constraint remains authoritative in every environment.
 */
@Component
public class FinanceBudgetOverlapLock {

    private final JdbcTemplate jdbcTemplate;

    public FinanceBudgetOverlapLock(DataSource dataSource) {
        jdbcTemplate = new JdbcTemplate(dataSource);
    }

    /** Acquires a transaction-scoped lock for the supplied bounded scope. */
    public void lock(String scope) {
        if (scope == null || scope.isBlank() || scope.length() > 512) {
            throw new IllegalArgumentException("budget overlap lock scope must be bounded and non-blank");
        }
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            String product = connection.getMetaData().getDatabaseProductName();
            if (!"PostgreSQL".equalsIgnoreCase(product)) {
                return null;
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "select pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                statement.setString(1, scope);
                statement.execute();
            }
            return null;
        });
    }
}
