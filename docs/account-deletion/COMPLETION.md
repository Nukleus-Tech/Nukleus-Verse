# Account deletion backend handoff

The existing Phase 1 lifecycle, Phase 2 safety adapters and Phase 3 security integration are reused.
The backend now connects Google-only reauthentication, provider removal clients, generation-pinned
storage cleanup, reviewed legacy ownership, automatic plan preparation and related-data cleanup.
All API and destructive switches remain off. No application server, cloud deployment, production
migration, cloud resource operation or real-account deletion was performed.

This is not unconditional production readiness. Synthetic local lifecycle tests establish behavior
for accounts with complete, trusted ownership and retention evidence. Historical evidence, the
origin of client-created Meshy tasks and shared-data retention decisions cannot be manufactured
locally. Resolve the prerequisites below before accepting real deletion requests.

## Authentication and revocation

Password login keeps its existing request/response fields and adds an opaque user bearer token
when integration is enabled. Successful active-account login upgrades legacy plaintext credentials
to salted BCrypt over a SHA-256 prehash; the prehash avoids BCrypt's password byte-length limit.
Registration and password changes use the same encoding in integration mode. Legacy plaintext
verification remains supported. No mass credential migration or User entity change is included.

Google-only accounts use POST /api/auth/google-challenge followed by POST /api/auth/google.
The existing Google verification library validates signature, issuer, audience and expiry against
Google public keys; a five-minute signed issued-at window and an atomically consumed random
nonce establish fresh authentication. Only an existing users.google_id equal to the verified
Google sub selects the account. Client IDs, user IDs, email claims alone and admin JWTs cannot
select a user. Unknown Google subjects cannot auto-link or register through this endpoint.

The existing PostgreSQL session authority stores token hashes, checks revocation on every user
request, and uses deletion-request epochs to invalidate pre-request tokens even if explicit
revocation loses its response. Pending users can reauthenticate for status and cancellation only.
Cancellation requires fresh authentication strictly before the stored deadline; it never restores
an old token or changes Admin Panel accountStatus. The default grace is exactly 168 elapsed hours.

The existing global advisory fence covers synchronous backend ingress and worker attempts.
Per-request locks, claim tokens, row locks and final snapshot checks prevent concurrent deletion.
No transaction stays open during provider or storage calls. Busy ingress returns 503, not a bypass.
All instances and other writers must participate; the database rollout latch is an operator
attestation, not proof that every external process has adopted the protocol.

See [the API contract](API.md) for request bodies, responses, scopes and errors.
The Google checks follow the official [ID-token verification guide](https://developers.google.com/identity/gsi/web/guides/verify-google-id-token)
and [nonce parameter documentation](https://developers.google.com/identity/gsi/web/reference/js-reference).

## Worker and related data

At the due time the original worker claims the request, validates the user/meeting state and
prepares an immutable cleanup plan from the reviewed inventory. It validates all objects,
providers, shared aliases and foreign keys before destructive cleanup. Storage generations are
journaled before removal. Provider and storage failures resume from durable receipts with the
existing bounded attempts and backoff. Unknown ownership, unknown dependencies and retention
holds fail closed rather than being silently skipped.

The final short transaction removes the deleting user's OTP records, structured meeting identity
references and user row. It preserves shared meeting rows, room codes, assets, statuses and other
participants. Ended hosted meetings receive a random non-identifying host marker; only the deleting
email is removed from participant lists. An ACTIVE host remains blocked with MEETING_REVIEW_REQUIRED.
The application does not silently end or transfer a live shared meeting. No recordings, PDFs,
slides or notes are deleted by the personal-data worker.

Sessions, ownership inventories, provider links, task proofs, plans and receipts cascade with the
user. Request history remains with user_id cleared by ON DELETE SET NULL, containing only lifecycle
timestamps, status and sanitized failure/attempt information. User.java, accountStatus, Admin
controllers and shared meeting schemas are unchanged.

## Storage ownership

New authenticated uploads keep the existing photo/archive endpoints but use server-assigned
users/{id}/photo/ and users/{id}/avatar/ keys, exclusive-owner metadata and exact generations.
The registry retains superseded objects. PNG/JPEG uploads retain usable filename extensions.
Cloud creation uses a does-not-exist precondition; a crash leaves a reservation rather than
silently losing ownership history.

Legacy images/ and images/glb/ objects require exact-generation evidence imported through
LegacyAssetOwnership.verifyAndRecord. Import verifies the allowlisted bucket, existing generation,
name and non-conflicting metadata. A digest must reference an independently reviewed upload or
provenance record. The method is not exposed to end users or the Admin Panel. A digest alone is
not a cryptographic proof of ownership; the trusted review process must establish the facts.
Multiple explicitly reviewed versions of a legacy object are supported. Shared URL aliases in
any other user or meeting block cleanup even when a proof was imported.

LegacyAssetOwnership.reconcileReservedUpload resolves an interrupted new-style upload only when
the exact object generation and exclusive-owner metadata match its existing reservation. It does
not guess that a missing upload never happened. Missing objects without prior generation receipts,
unknown versions, soft-deleted objects, holds and untracked historical copies require reconciliation.

Remote archival still requires explicitly reviewed HTTPS CDN hosts, no redirects and a 64 MiB cap.
Ownership is not inferred from a URL or by listing an entire bucket. Provider output URLs are not
sent to Storage.delete.

## Provider cleanup

MeshyTaskCleanup uses authenticated GET and DELETE requests to the documented
https://api.meshy.ai/openapi/v1/{kind}/{taskId} endpoints. Supported kinds are image-to-image,
image-to-3d, rigging and animations. A current task ID and all reviewed historical tasks must be
owned by the same user. Children are removed before parents. The client retries in-progress/409,
408, 429 and server failures. A 404 is accepted only after a previously successful verification of
that owned task; an unverified missing task blocks cleanup.

VerifiedProviderAssets records hashes of output locations returned by those authenticated GETs,
including model URLs, image URLs and rigging/animation results. Unarchived assets.meshy.ai links
must match that evidence; signed-query changes do not change the underlying output identity.
Other user/meeting references to the same output block deletion. Arbitrary remote URLs are not
adopted. Persisted evidence supports retry after a provider task has already disappeared.

Task GET access proves application-level API-key access, not end-user ownership. The latter requires
trusted creation history or independently audited evidence through CleanupInventoryStore.recordVerifiedMeshyTask.
Client-supplied IDs in the existing avatar-save flow remain claims only. Existing avatar generation,
walking, idle and sitting actions are not changed.

Provider references: [Image to 3D](https://docs.meshy.ai/en/api/image-to-3d),
[Image to Image](https://docs.meshy.ai/en/api/image-to-image), [Rigging](https://docs.meshy.ai/en/api/rigging)
and [Animation](https://docs.meshy.ai/en/api/animation).

GoogleGrantCleanup removes only this application's verified link or OAuth grant, never a Google
account. ID-token-only sign-in has no refresh token to revoke. If an application OAuth grant exists,
its verified token must be imported from a trusted server-side exchange/reconciliation; possession
of googleId is insufficient. Tokens are encrypted with AES-256-GCM, per-record random nonces and
user/subject binding. The configured encryption key must be stable and securely managed.

The grant client POSTs form-encoded token to https://oauth2.googleapis.com/revoke. It records
completion only after success or an invalid_token response for the previously verified grant,
then clears the ciphertext. Other errors retain the account and retry or require review.
Google describes application grant revocation in its [OAuth documentation](https://developers.google.com/identity/protocols/oauth2/web-server#tokenrevoke);
revocation can affect all scopes granted to the same application project.

## Evidence and recovery

There is deliberately no public approval/import API. The trusted operator or future server-side
creation integration must invoke these existing Spring beans under OperationFence.acquire(true):

1. Import exact-generation legacy ownership, reconcile upload reservations, record verified Meshy
   task provenance and, where applicable, encrypted Google grant evidence.
2. Review all six ApprovedCleanupPlan.ReviewDomain values, including unlinked historical data,
   related tables, shared aliases and logs/backups/retention. Call
   CleanupInventoryStore.reviewCoverage(userId, evidenceSha256, allDomains).
3. The due worker prepares the existing immutable plan automatically. Its snapshot and inventory
   hashes must still match the review. Inventory mutation cannot silently broaden a running plan.
4. Transient failures retry automatically up to max-attempts. After resolving a non-transient hold,
   CleanupPreparation.retryReviewedFailure can requeue a FAILED request under the exclusive fence.
   It does not reset the deadline or attempt count and cannot bypass subsequent checks.

Never invoke evidence methods from an untrusted client payload, fabricate review hashes or insert
provider completion receipts to bypass actual deletion. Exhausted attempts, changed immutable plans
and ambiguous legacy evidence require an explicit operational remediation decision.

## Configuration and migrations

The optional application-account-deletion.properties profile is not active by default. It selects
Hibernate validate instead of update, UTC JDBC timestamps, 168h grace and safe false switches.
Existing application.properties, dependency versions and earlier migrations are untouched.
Configuration can be supplied through deployment environment variables; relaxed binding removes
hyphens, for example NUKLEUS_ACCOUNTDELETION_GRACEPERIOD=168h.

| Property under nukleus.account-deletion | Default | Activation requirement |
| --- | --- | --- |
| integration.enabled | false | Every instance and client supports verified bearer authentication |
| api-enabled | false | Reauthentication, cancellation and revocation validated |
| scheduler-enabled, destructive-enabled | false | Both require explicit destructive approval |
| grace-period | 168h | Positive duration; existing deadlines never change |
| recent-authentication | 10m | Fresh password or nonce-bound Google authentication |
| cleanup.automatic-preparation-enabled | false | Migration 03 and complete reviewed inventory |
| cleanup.anonymize-meeting-references | false | Approved structured-reference retention policy |
| cleanup.provider-calls-enabled | false | Approved provider credentials and permission to remove app-owned data |
| cleanup.google-client-ids | empty | Exact audiences for this application's authorized Google clients |
| cleanup.meshy-api-key | empty | Secret from the same verified Meshy application/account |
| cleanup.encryption-key | empty | Base64 encoding of a securely generated 32-byte AES key |
| allowed-buckets, integration.archive-hosts | empty | Explicit reviewed storage/CDN allowlists |

Keep provider tokens, API keys and encryption material out of source, logs, command history and
review manifests. No sample production credentials are supplied.

There is no Flyway or Liquibase runner. Deployment does not run migrations automatically.
Apply the versioned files under src/main/resources/db/account-deletion using the existing approved
manual migration process and an external migration ledger:

1. V20261008_01__create_account_deletion_requests.sql
2. V20261008_02__account_deletion_integration.sql
3. V20261008_03__cleanup_evidence_and_google_reauthentication.sql

Migration 03 adds seven tables: google_challenges, google_links, legacy_objects, provider_tasks,
provider_assets, coverage and plan_bindings, all prefixed account_deletion_. Personal tables link
to users(id) with ON DELETE CASCADE; task assets and plan bindings cascade through their parent.
It alters no existing table or user record. Migration 01's nullable user_id FK uses ON DELETE SET NULL.
All lifecycle timestamps are timestamptz. Each SQL file has BEGIN/COMMIT and lock/statement timeouts.
Use psql -v ON_ERROR_STOP=1 -f <reviewed-file> only in a future authorized target environment;
do not rerun a recorded migration or rely on Hibernate auto-update.

## Future activation procedure

1. Obtain separate cloud/deployment approval. Verify the intended instance, schema, inventory and
   dependencies; take and restore-test a backup. Review storage versioning, soft-delete, holds and
   provider retention. No such production verification has been performed here.
2. Apply missing migrations in order to an isolated staging clone, record checksums, and validate
   mappings with ddl-auto=validate. Run the isolated tests and test-account deployment checks.
3. Configure Google audiences, application-scoped provider credentials, an encryption key, bucket
   and archive-host allowlists. Reconcile ownership, grant history and all retention domains.
4. Ship client bearer/Google nonce support. Roll out integration to every backend instance while
   deletion APIs and workers remain off. Drain old versions and account for direct/async writers.
5. After verifying every route and instance, an authorized operator records enforcement_required=true
   and rollout_approved_at in account_deletion_integration_state. Never disable enforcement while
   pending users exist or roll back to an older unguarded binary.
6. Separately approve the API. Validate request, 168h deadline, revocation, restrictions and
   cancellation on synthetic/test accounts before permitting real requests.
   Keep status/cancellation available while requests are pending; do not switch api-enabled off
   to pause new work. Use the worker switches to stop destructive processing.
7. Approve structured meeting anonymization and provider calls only after policy and permission
   review. Enable automatic preparation only with complete evidence.
8. Separately approve both worker switches. Monitor bounded retries, sanitized errors, connection
   pool usage and retained-data exceptions. Stopping the scheduler is safe; disabling pending
   enforcement is not. Validate failover and load before broad rollout.

## Remaining readiness limits

Local backend source and mock-backed lifecycle coverage do not establish that only UI and cloud
deployment remain. Additional prerequisites are:

- Legacy ownership, historical provider-task associations and OAuth-grant coverage must be
  reconciled. The old Unity-side task creation does not provide trustworthy server ownership;
  a trusted creation/provenance integration or independently audited history is still necessary.
- Active hosted meetings need an approved resolution procedure. Shared recordings, documents,
  names/free text, logs and backups can contain personal data; automatic redaction or deletion of
  those shared contents is not implemented and must not be claimed. Review retention obligations.
- Unknown tables, untracked copies, stale/ambiguous googleId mappings or email collisions block
  deletion until reconciled. Live provider permissions, Cloud SQL schema and storage policies remain
  unverified because cloud access was prohibited.
- The original admin JWT signing secret and remaining inactive legacy plaintext credentials are
  pre-existing security debt. The admin system was not redesigned. Resolve key management and
  credential rollout during the broader production security review.
- No production networking, Unity/VR transport, distributed load/failover or scheduled unattended
  soak test was run. Backend HTTP enforcement does not revoke sessions in an external realtime
  service. Expired session/nonce retention and operational recovery must be approved for rollout.
  Rate-limit credential and Google challenge endpoints at trusted ingress before public activation.

Use [the test results](COMPLETION-TEST-RESULTS.md) and [the exact change manifest](COMPLETION-CHANGES.md)
for the local verification scope. Do not enable real account deletion while these requirements are unresolved.
