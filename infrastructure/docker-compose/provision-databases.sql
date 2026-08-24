-- PostgreSQL does not permit CREATE DATABASE in a transaction block. psql's \gexec executes each
-- emitted statement independently, making this safe to rerun after the initial Compose bootstrap.
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
