-- MANUAL, reviewed migration; never run automatically or against production during development.
-- Apply AFTER V20261008_01. No existing table is altered; all user identifiers cascade away.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

CREATE TABLE account_deletion_integration_state (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    schema_version integer NOT NULL CHECK (schema_version = 1),
    enforcement_required boolean NOT NULL DEFAULT false,
    rollout_approved_at timestamptz
);
INSERT INTO account_deletion_integration_state(singleton, schema_version) VALUES (true, 1);

CREATE TABLE account_deletion_sessions (
    token_hash char(64) PRIMARY KEY,
    user_id bigint NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    authenticated_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL CHECK (expires_at > authenticated_at),
    scope varchar(20) NOT NULL CHECK (scope IN ('FULL','DELETION_ONLY')),
    request_epoch bigint NOT NULL DEFAULT 0 CHECK (request_epoch >= 0),
    revoked_at timestamptz,
    revoked_by_request bigint REFERENCES account_deletion_requests(id) ON DELETE SET NULL
);
CREATE INDEX account_deletion_sessions_user ON account_deletion_sessions(user_id);
CREATE INDEX account_deletion_sessions_expiry ON account_deletion_sessions(expires_at);

-- Reservation precedes cloud upload: interrupted or unjournaled uploads require reconciliation.
CREATE TABLE account_deletion_owned_objects (
    bucket text NOT NULL,
    object_name text NOT NULL,
    user_id bigint NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    generation bigint CHECK (generation > 0),
    kind varchar(10) NOT NULL CHECK (kind IN ('PHOTO','AVATAR')),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(bucket, object_name),
    CHECK (object_name LIKE 'users/' || user_id::text || '/%')
);
CREATE INDEX account_deletion_owned_objects_user ON account_deletion_owned_objects(user_id);

-- A trusted offline review installs an immutable plan; there is deliberately no approval HTTP API.
CREATE TABLE account_deletion_cleanup_plans (
    id uuid PRIMARY KEY,
    request_id bigint NOT NULL UNIQUE REFERENCES account_deletion_requests(id) ON DELETE CASCADE,
    user_id bigint NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    plan jsonb NOT NULL CHECK (jsonb_typeof(plan) = 'object'),
    approved_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX account_deletion_cleanup_plans_user ON account_deletion_cleanup_plans(user_id);
CREATE TABLE account_deletion_generation_receipts (
    plan_id uuid NOT NULL REFERENCES account_deletion_cleanup_plans(id) ON DELETE CASCADE,
    bucket text NOT NULL,
    object_name text NOT NULL,
    generation bigint NOT NULL CHECK (generation > 0),
    verified_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(plan_id, bucket, object_name, generation)
);
-- Evidence of completed provider cleanup by a trusted operator, not a fabricated provider API.
-- Reference hashes are sensitive pseudonymous data; cascade removes them with the user's plan.
CREATE TABLE account_deletion_provider_receipts (
    plan_id uuid NOT NULL REFERENCES account_deletion_cleanup_plans(id) ON DELETE CASCADE,
    provider varchar(40) NOT NULL CHECK (provider IN ('GOOGLE_APPLICATION_GRANT','MESHY_TASK')),
    reference_hash char(64) NOT NULL,
    reviewed_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz NOT NULL,
    PRIMARY KEY(plan_id, provider)
);

CREATE FUNCTION account_deletion_immutable_plan() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Cleanup plan updates are forbidden';
END $$;
CREATE TRIGGER account_deletion_plan_no_update BEFORE UPDATE ON account_deletion_cleanup_plans
    FOR EACH ROW EXECUTE FUNCTION account_deletion_immutable_plan();

COMMENT ON TABLE account_deletion_sessions IS 'Opaque end-user token digests only; never admin JWTs or raw tokens.';
COMMENT ON TABLE account_deletion_cleanup_plans IS 'Personal audit evidence cascades on user deletion; minimal Phase 1 completion remains.';
COMMIT;
