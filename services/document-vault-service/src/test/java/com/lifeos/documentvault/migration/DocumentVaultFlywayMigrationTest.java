package com.lifeos.documentvault.migration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Verifies the dedicated H2 migration path and the no-document-bytes persistence invariant. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:document-vault-migration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=document-vault-migration-password",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration-h2",
    "document-vault.idempotency-secret=migration-idempotency-secret",
    "document-vault.audit-client-fingerprint-secret=migration-audit-secret",
    "document-vault.proof-outbox.relay-enabled=false",
    "identity.workload-token=migration-workload-token"
})
class DocumentVaultFlywayMigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void migratesMetadataIdempotencyAndRedactedAuditTablesWithoutAByteColumn() {
        Integer tables = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where table_name in "
                        + "('VAULT_DOCUMENT', 'DOCUMENT_COMMAND_IDEMPOTENCY', 'DOCUMENT_VAULT_SECURITY_AUDIT_EVENT', "
                        + "'DOCUMENT_PROOF_REQUEST', 'DOCUMENT_PROOF_OUTBOX_EVENT', "
                        + "'DOCUMENT_PROOF_OUTBOX_DEAD_LETTER')",
                Integer.class);
        Integer byteColumns = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.columns where table_name = 'VAULT_DOCUMENT' "
                        + "and column_name in ('CONTENT', 'CONTENT_BYTES', 'DATA', 'BLOB')",
                Integer.class);

        assertThat(tables).isEqualTo(6);
        assertThat(byteColumns).isZero();
    }
}
