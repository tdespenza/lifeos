-- H2 test equivalent of the PostgreSQL baseline migration.
CREATE TABLE IF NOT EXISTS goal (
    id UUID NOT NULL,
    title VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_goal PRIMARY KEY (id)
);
