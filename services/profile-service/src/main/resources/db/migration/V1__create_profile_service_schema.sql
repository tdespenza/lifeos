-- Profile service owns this schema and never joins identity-service tables. Account identifiers
-- are opaque UUID references verified through Identity's workload-authenticated decision boundary.
CREATE TABLE personal_profile (
    id UUID NOT NULL,
    owner_account_id UUID NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    locale VARCHAR(35) NOT NULL,
    time_zone VARCHAR(64) NOT NULL,
    pronouns VARCHAR(80),
    bio VARCHAR(1000),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_personal_profile PRIMARY KEY (id),
    CONSTRAINT uk_personal_profile_owner_tenant UNIQUE (owner_account_id, tenant_id),
    CONSTRAINT ck_personal_profile_version CHECK (version >= 0)
);

CREATE INDEX idx_personal_profile_owner_tenant
    ON personal_profile (owner_account_id, tenant_id);

CREATE TABLE profile_preferences (
    profile_id UUID NOT NULL,
    theme VARCHAR(16) NOT NULL,
    week_start VARCHAR(16) NOT NULL,
    daily_digest_enabled BOOLEAN NOT NULL,
    default_goal_horizon_days INTEGER NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_profile_preferences PRIMARY KEY (profile_id),
    CONSTRAINT fk_profile_preferences_profile FOREIGN KEY (profile_id)
        REFERENCES personal_profile (id) ON DELETE CASCADE,
    CONSTRAINT ck_profile_preferences_theme CHECK (theme IN ('SYSTEM', 'LIGHT', 'DARK')),
    CONSTRAINT ck_profile_preferences_week_start CHECK (week_start IN ('MONDAY', 'SUNDAY')),
    CONSTRAINT ck_profile_preferences_horizon CHECK (default_goal_horizon_days BETWEEN 1 AND 365),
    CONSTRAINT ck_profile_preferences_version CHECK (version >= 0)
);

CREATE TABLE profile_privacy_settings (
    profile_id UUID NOT NULL,
    profile_visibility VARCHAR(16) NOT NULL,
    share_activity_with_household BOOLEAN NOT NULL,
    allow_household_directory BOOLEAN NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_profile_privacy_settings PRIMARY KEY (profile_id),
    CONSTRAINT fk_profile_privacy_settings_profile FOREIGN KEY (profile_id)
        REFERENCES personal_profile (id) ON DELETE CASCADE,
    CONSTRAINT ck_profile_privacy_visibility CHECK (profile_visibility IN ('PRIVATE', 'HOUSEHOLD')),
    CONSTRAINT ck_profile_privacy_version CHECK (version >= 0)
);

CREATE TABLE profile_ai_personalization_settings (
    profile_id UUID NOT NULL,
    consent_granted BOOLEAN NOT NULL,
    personalization_enabled BOOLEAN NOT NULL,
    allowed_context_categories VARCHAR(160) NOT NULL,
    consent_updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_profile_ai_personalization_settings PRIMARY KEY (profile_id),
    CONSTRAINT fk_profile_ai_personalization_profile FOREIGN KEY (profile_id)
        REFERENCES personal_profile (id) ON DELETE CASCADE,
    CONSTRAINT ck_profile_ai_personalization_version CHECK (version >= 0)
);

CREATE TABLE household (
    id UUID NOT NULL,
    owner_account_id UUID NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    name VARCHAR(120) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_household PRIMARY KEY (id),
    CONSTRAINT ck_household_version CHECK (version >= 0)
);

CREATE INDEX idx_household_owner_tenant
    ON household (owner_account_id, tenant_id);

CREATE TABLE household_member (
    id UUID NOT NULL,
    household_id UUID NOT NULL,
    member_account_id UUID NOT NULL,
    relationship_type VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_household_member PRIMARY KEY (id),
    CONSTRAINT fk_household_member_household FOREIGN KEY (household_id)
        REFERENCES household (id) ON DELETE CASCADE,
    CONSTRAINT uk_household_member_account UNIQUE (household_id, member_account_id),
    CONSTRAINT ck_household_member_relationship CHECK (
        relationship_type IN ('SELF', 'SPOUSE', 'PARTNER', 'CHILD', 'PARENT', 'SIBLING', 'OTHER')
    ),
    CONSTRAINT ck_household_member_version CHECK (version >= 0)
);

CREATE INDEX idx_household_member_account
    ON household_member (member_account_id, household_id);

CREATE TABLE household_member_permission (
    household_member_id UUID NOT NULL,
    permission VARCHAR(32) NOT NULL,
    CONSTRAINT pk_household_member_permission PRIMARY KEY (household_member_id, permission),
    CONSTRAINT fk_household_member_permission_member FOREIGN KEY (household_member_id)
        REFERENCES household_member (id) ON DELETE CASCADE,
    CONSTRAINT ck_household_member_permission_value CHECK (
        permission IN ('HOUSEHOLD_READ', 'MEMBERS_READ', 'MEMBERS_MANAGE')
    )
);

-- Each mutation has one caller/tenant/key reservation. Digests are keyed HMAC-SHA-256 values;
-- neither raw idempotency keys nor request data are stored in this table.
CREATE TABLE profile_mutation_idempotency (
    id UUID NOT NULL,
    actor_account_id UUID NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    operation VARCHAR(64) NOT NULL,
    resource_id UUID NOT NULL,
    idempotency_key_hash VARCHAR(64) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    expected_version BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL,
    response_snapshot TEXT,
    response_status INTEGER,
    response_location VARCHAR(256),
    completed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_profile_mutation_idempotency PRIMARY KEY (id),
    CONSTRAINT uk_profile_mutation_idempotency_scope_key UNIQUE (
        actor_account_id, tenant_id, operation, idempotency_key_hash
    ),
    CONSTRAINT ck_profile_mutation_idempotency_expected_version CHECK (expected_version >= -1),
    CONSTRAINT ck_profile_mutation_idempotency_state CHECK (
        (state = 'PENDING'
            AND completed_at IS NULL
            AND response_snapshot IS NULL
            AND response_status IS NULL
            AND response_location IS NULL)
        OR (state = 'COMPLETED'
            AND completed_at IS NOT NULL
            AND response_snapshot IS NOT NULL
            AND response_status IN (200, 201)
            AND ((response_status = 200 AND response_location IS NULL)
                OR (response_status = 201 AND response_location IS NOT NULL)))
    )
);

CREATE INDEX idx_profile_mutation_idempotency_resource
    ON profile_mutation_idempotency (resource_id);

-- Audit facts intentionally exclude profile names, relationship labels, request payloads, raw
-- client addresses, bearer values, and idempotency keys. Outcome code is a bounded enum-like tag.
CREATE TABLE profile_security_audit_event (
    id UUID NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    account_id UUID,
    correlation_id VARCHAR(128) NOT NULL,
    client_fingerprint VARCHAR(64) NOT NULL,
    outcome_code VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_profile_security_audit_event PRIMARY KEY (id)
);

CREATE INDEX idx_profile_security_audit_event_account_time
    ON profile_security_audit_event (account_id, occurred_at);
