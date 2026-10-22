-- Manual migration after _01 and _02. No production execution or automatic Hibernate DDL.
BEGIN;
SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='60s';
CREATE TABLE account_deletion_google_challenges (
    nonce_hash char(64) PRIMARY KEY, created_at timestamptz NOT NULL, expires_at timestamptz NOT NULL,
    consumed_at timestamptz, CHECK(expires_at > created_at)
);
CREATE INDEX account_deletion_google_challenges_expiry ON account_deletion_google_challenges(expires_at);
CREATE TABLE account_deletion_google_links (
    user_id bigint PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    grant_id uuid NOT NULL UNIQUE,
    subject_hash char(64) NOT NULL,
    client_id text NOT NULL,
    mode text NOT NULL CHECK(mode IN ('ID_TOKEN_ONLY','OAUTH_GRANT')),
    encrypted_token bytea,
    revoked_at timestamptz,
    CHECK(mode <> 'OAUTH_GRANT' OR encrypted_token IS NOT NULL OR revoked_at IS NOT NULL)
);
CREATE TABLE account_deletion_legacy_objects (
    bucket text NOT NULL, object_name text NOT NULL, generation bigint NOT NULL CHECK(generation>0),
    user_id bigint NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    evidence_hash char(64) NOT NULL, reviewed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(bucket,object_name,generation), CHECK(object_name LIKE 'images/%')
);
CREATE INDEX account_deletion_legacy_objects_user ON account_deletion_legacy_objects(user_id);
CREATE TABLE account_deletion_provider_tasks (
    task_id uuid PRIMARY KEY, user_id bigint NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind text NOT NULL CHECK(kind IN ('image-to-image','image-to-3d','rigging','animations')),
    evidence_hash char(64) NOT NULL, verified_at timestamptz, deleted_at timestamptz,
    reviewed_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX account_deletion_provider_tasks_user ON account_deletion_provider_tasks(user_id);
CREATE TABLE account_deletion_provider_assets (
    task_id uuid NOT NULL REFERENCES account_deletion_provider_tasks(task_id) ON DELETE CASCADE,
    url_hash char(64) NOT NULL, PRIMARY KEY(task_id,url_hash)
);
CREATE INDEX account_deletion_provider_assets_url ON account_deletion_provider_assets(url_hash);
CREATE TABLE account_deletion_coverage (
    user_id bigint PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    snapshot_fingerprint char(64) NOT NULL, inventory_fingerprint char(64) NOT NULL,
    evidence_hash char(64) NOT NULL, reviewed_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE account_deletion_plan_bindings (
    plan_id uuid PRIMARY KEY REFERENCES account_deletion_cleanup_plans(id) ON DELETE CASCADE,
    inventory_fingerprint char(64) NOT NULL
);
COMMIT;
