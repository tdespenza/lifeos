-- The assistant stores only confirmation metadata and keyed request fingerprints. Raw tool
-- arguments, bearer credentials, and downstream response bodies remain outside this ledger.
CREATE TABLE assistant_tool_confirmation (
    id UUID NOT NULL,
    conversation_id UUID NOT NULL,
    owner_account_id UUID NOT NULL,
    operation VARCHAR(32) NOT NULL,
    idempotency_key_hash VARCHAR(64) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    confirmed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_assistant_tool_confirmation PRIMARY KEY (id),
    CONSTRAINT uq_assistant_tool_confirmation_key UNIQUE (conversation_id, owner_account_id, idempotency_key_hash),
    CONSTRAINT ck_assistant_tool_confirmation_operation CHECK (
        operation IN ('DRAFT_TASK', 'DRAFT_GOAL', 'DRAFT_FINANCIAL_NOTE')),
    CONSTRAINT ck_assistant_tool_confirmation_key_hash CHECK (idempotency_key_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_assistant_tool_confirmation_request_fingerprint CHECK (request_fingerprint ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_assistant_tool_confirmation_owner
    ON assistant_tool_confirmation (owner_account_id, confirmed_at DESC, id);
