-- PostgreSQL does not permit CREATE DATABASE in a transaction block. psql's \gexec executes each
-- emitted statement independently, making this safe to rerun after the initial Compose bootstrap.
-- Keep this session-level lock (rather than a transaction-level lock) until both \gexec operations
-- have completed: each generated CREATE DATABASE runs in its own transaction.
-- Bound contention at 45 seconds so a stalled bootstrap fails with PostgreSQL's lock-timeout
-- diagnostic instead of blocking indefinitely; normal local bootstrap completes well within it.
SET lock_timeout = '45s';
SELECT pg_advisory_lock(hashtextextended('lifeos.provision-databases', 0));

SELECT format('CREATE DATABASE %I', 'lifeos_identity')
WHERE NOT EXISTS (
    SELECT 1
    FROM pg_database
    WHERE datname = 'lifeos_identity'
)
\gexec

SELECT format('CREATE DATABASE %I', 'lifeos_task_goal')
WHERE NOT EXISTS (
    SELECT 1
    FROM pg_database
    WHERE datname = 'lifeos_task_goal'
)
\gexec

SELECT pg_advisory_unlock(hashtextextended('lifeos.provision-databases', 0));
