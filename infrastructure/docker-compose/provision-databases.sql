-- psql meta-command file used for an existing local Postgres volume. The Docker entrypoint runs
-- init-databases.sql only once; this file safely creates later-added bounded-context databases.
SELECT format('CREATE DATABASE %I', requested.name)
FROM (
    VALUES
        ('lifeos_identity'),
        ('lifeos_task_goal'),
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
