-- PostgreSQL does not permit CREATE DATABASE in a transaction block. psql's \gexec executes each
-- emitted statement independently, making this safe to rerun after the initial Compose bootstrap.
-- Keep this session-level lock (rather than a transaction-level lock) until both \gexec operations
-- have completed: each generated CREATE DATABASE runs in its own transaction.
-- Bound contention at 45 seconds so a stalled bootstrap fails with PostgreSQL's lock-timeout
-- diagnostic instead of blocking indefinitely; normal local bootstrap completes well within it.
-- The Docker entrypoint runs init-databases.sql only once; this file safely provisions databases
-- introduced later for an existing local PostgreSQL volume.
SET lock_timeout = '45s';
SELECT pg_advisory_lock(hashtextextended('lifeos.provision-databases', 0));

SELECT format('CREATE DATABASE %I', requested.name)
FROM (
    VALUES
        ('lifeos_identity'),
        ('lifeos_task_goal')
) AS requested(name)
WHERE NOT EXISTS (
    SELECT 1
    FROM pg_database
    WHERE datname = requested.name
)
\gexec

SELECT format('CREATE DATABASE %I', requested.name)
FROM (
    VALUES
        ('lifeos_profile'),
        ('lifeos_notification'),
        ('lifeos_calendar'),
        ('lifeos_finance'),
        ('lifeos_document_vault'),
        ('lifeos_media'),
        ('lifeos_ai_assistant'),
        ('lifeos_analytics'),
        ('lifeos_trust_ledger')
) AS requested(name)
WHERE NOT EXISTS (
    SELECT 1
    FROM pg_database
    WHERE datname = requested.name
)
\gexec

SELECT pg_advisory_unlock(hashtextextended('lifeos.provision-databases', 0));
