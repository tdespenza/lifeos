ALTER TABLE vault_document
    ADD COLUMN content_search_token_digests VARCHAR(16640) NOT NULL DEFAULT '';

ALTER TABLE vault_document
    ADD CONSTRAINT ck_vault_document_search_tokens
    CHECK (content_search_token_digests ~ '^$|^;[0-9a-f;]*$');
