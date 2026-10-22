# Account deletion Phase 3 integration and deployment requirements

Historical Phase 3 checkpoint. The current implementation, resolved limitations and remaining
activation requirements are in [the completion handoff](../COMPLETION.md).

Phase 3 connects the existing Phase 1 lifecycle and Phase 2 safety adapters to password-authenticated end-user sessions, synchronous HTTP routes, owned uploads and a durable cleanup journal. It is prepared for local integration testing, not approved for production activation. All deletion and integration switches remain false by default.

The application was not started or deployed. Google Cloud billing, Cloud SQL, cloud storage and real users were not accessed. Tests use random schemas in a new loopback PostgreSQL database, MockMvc and mocked Storage.

## Authentication and access enforcement

The pre-existing JWT mechanism authenticates administrators only. It remains responsible for admin routes. End-user login previously returned only profile fields, so it could not securely identify a user on subsequent requests.

When integration is enabled, the existing password login issues an opaque 256-bit token prefixed with nv1_. PostgreSQL stores its SHA-256 digest, authenticated time, expiry, scope and deletion-request epoch. The raw token appears only in the login response. The existing Spring Security filter invokes the new integration adapter; there is no second independently registered authentication filter.

Every protected request reloads the session and user, checks expiry and the current admin BLOCKED state, and invokes the Phase 2 end-user/pending-deletion adapters. Admin JWTs, arbitrary principal objects, request-body IDs and email addresses cannot establish user identity. Mixed or admin authorities do not become ROLE_USER.

Committing a deletion request invalidates every earlier session epoch, even if the subsequent explicit revocation update fails. Cancellation does not resurrect an old token. Pending users must reauthenticate with their password to receive a DELETION_ONLY token, usable for status and cancellation only. Fresh credential authentication within the existing ten-minute default is required for request/cancellation. Sessions expire after eight hours by default; no refresh-token mechanism was added.

The integration checks all existing synchronous /api routes. Login/registration remain entry points; admin and OTP routes retain their existing admin authentication. User routes reject admin JWTs. Meeting create/join/leave and avatar writes verify the authenticated owner's email; meeting end/update/upload require the actual host. Personal objects cannot be attached as shared meeting files. Avatar reads remain available to active authenticated users so existing meeting avatar lookup is not redesigned.

Existing meeting records and admin controllers are not rewritten. A pending participant can still appear in existing shared records; those references block permanent deletion. No meeting is deleted or silently anonymized.

## In flight requests and worker fencing

A PostgreSQL session-level shared advisory lock covers synchronous HTTP ingress, including admin writes and login. Deletion request/cancellation and each worker attempt require the corresponding exclusive lock. A busy lock returns ACCOUNT_OPERATION_BUSY instead of proceeding. This deliberately coarse gate prevents an in-flight application operation from racing cleanup across instances.

The original per-request advisory lock, row locking, claim tokens, bounded retries and final snapshot checks remain in place. No database transaction stays open across storage operations; advisory-lock connections do stay allocated. Size the connection pool for the fence plus ordinary repository transactions and worker locks.

This is designed for the inspected synchronous backend. There are no non-deletion background writers or backend WebSocket handlers in the inspected source. Future async/background writers must explicitly participate before activation. External Unity/realtime infrastructure is outside this backend's enforcement and has not been tested or modified.

A database disconnect or failover must fail closed. Lease liveness is checked, but production failover, long-running upload termination, pool exhaustion and distributed load have not been exercised. Do not roll out alongside old instances that lack the guard. Direct database writers bypass application advisory locks and must be eliminated or separately fenced.

PostgreSQL documents the distinction between session and transaction advisory locks in its [locking documentation](https://www.postgresql.org/docs/17/explicit-locking.html). The integration uses the existing Spring Security context/filter architecture described in the [official reference](https://docs.spring.io/spring-security/reference/6.5/servlet/authentication/architecture.html).

## Storage ownership and cleanup journal

With integration enabled, upload-image and archive-remote-file write new personal assets under users/{verifiedUserId}/photo/ or users/{verifiedUserId}/avatar/. Original filenames cannot change the object key. Each upload reserves a registry entry before cloud I/O, uses create-if-absent, sets nukleusUserId and nukleusExclusiveOwner metadata, and persists the exact returned object generation before returning its URL.

An interrupted upload leaves a reservation with no generation. That blocks plan approval until reconciled; the implementation does not guess whether an orphan exists. Historical and superseded registered objects must be included in the approved plan.

Meeting files remain shared and stay outside the personal ownership registry. Legacy images/ and images/glb/ objects are not automatically adopted or deleted. A stored URL alone is never sufficient evidence. Existing avatar fields, Google subject and Meshy task ID remain unchanged.

Remote GLB archival requires explicit exact HTTPS provider host allowlisting, no redirects, connect/read timeouts and a 64 MiB cap. The default host allowlist is empty. Provider/CDN hostnames must be verified from the actual approved generation workflow before enabling this route in integration mode. No provider endpoint or hostname is invented.

The existing Phase 2 gateway is composed with JdbcCleanupJournal, JdbcSessionAuthority and the existing ownership, inventory, provider and revocation adapters. It is not replaced with a duplicate deletion lifecycle.

A trusted offline reviewer must install an immutable ApprovedCleanupPlan covering all six Phase 2 review domains, including historical objects, shared aliases, external identities and logs/backups/retention. JdbcCleanupJournal.installReviewedPlan is not an HTTP endpoint; it requires an exclusive fence, an eligible PENDING request, matching snapshot and complete registry coverage. There is no auto-approval.

Storage-generation receipts are persisted atomically before deletion. Retry attempts use those durable receipts, allowing an already-deleted approved generation to be recognized safely. A new generation or unverified missing object cannot be silently accepted.

## Provider and shared data limitations

There is no verified provider-ownership journal in the legacy application. A Google subject alone cannot prove an OAuth grant, and a client-supplied Meshy task ID cannot prove the right to delete a provider task.

No live Google/Meshy removal client is registered. The existing Phase 2 provider adapter blocks nonempty references unless a trusted operator has already completed the appropriate application-scoped provider cleanup and recorded evidence bound to the exact plan, provider and SHA-256 reference digest. A receipt is evidence of completed work, not permission to skip it. Never delete the person's Google account.

GoogleAuthService exists, but the inspected controller has no implemented Google-login endpoint and the verifier has no configured audience. Phase 3 does not invent a Google login or treat googleId as authentication. Google-only accounts still require a separately implemented and tested audience-bound sign-in/reauthentication integration.

Users with any current meeting ID, hosted meeting or participant-email reference remain blocked with MEETING_REVIEW_REQUIRED. A retention/host-transfer/anonymization policy is still needed. Unknown foreign keys also block cleanup. The only newly allowed cascades are the reviewed user_id references from sessions, owned objects and cleanup plans.

Phase 1 still deletes eligible OTP rows and the user in its final transaction. The new session/ownership/plan tables cascade with the user; generation/provider receipts cascade with the plan. Phase 1 request history remains with user_id cleared through ON DELETE SET NULL. No raw token, email, URL or provider subject is retained in that minimal completion row.

## Defaults and configuration

No application.properties, YAML, existing SQL migration or dependency file changed.

| Property | Environment variable | Default |
| --- | --- | --- |
| nukleus.account-deletion.integration.enabled | NUKLEUS_ACCOUNTDELETION_INTEGRATION_ENABLED | false |
| nukleus.account-deletion.api-enabled | NUKLEUS_ACCOUNTDELETION_APIENABLED | false |
| nukleus.account-deletion.scheduler-enabled | NUKLEUS_ACCOUNTDELETION_SCHEDULERENABLED | false |
| nukleus.account-deletion.destructive-enabled | NUKLEUS_ACCOUNTDELETION_DESTRUCTIVEENABLED | false |
| nukleus.account-deletion.grace-period | NUKLEUS_ACCOUNTDELETION_GRACEPERIOD | 168h |
| nukleus.account-deletion.recent-authentication | NUKLEUS_ACCOUNTDELETION_RECENTAUTHENTICATION | 10m |
| nukleus.account-deletion.integration.session-lifetime | NUKLEUS_ACCOUNTDELETION_INTEGRATION_SESSIONLIFETIME | 8h |
| nukleus.account-deletion.integration.archive-hosts | NUKLEUS_ACCOUNTDELETION_INTEGRATION_ARCHIVEHOSTS | empty |
| nukleus.account-deletion.allowed-buckets | NUKLEUS_ACCOUNTDELETION_ALLOWEDBUCKETS | empty |

The original max-attempts (5), retry-delay (15m), batch-size (10) and 60-second scheduler interval remain unchanged. Requests do not extend an existing deadline. At the HTTP boundary a revoked/pending ordinary token is rejected; reauthenticate and use status rather than repeatedly submitting deletion-request.

## API contract for later client integration

Use HTTPS and Authorization: Bearer <end-user accessToken>. Admin JWTs are not accepted on user routes. Keep tokens out of logs, URLs and analytics. Clients must adopt bearer headers before integration mode can be enabled without breaking their existing unauthenticated requests. No Unity or UI changes are part of this phase.

POST /api/auth/login retains email/password input. Example synthetic request:

~~~json
{"email":"synthetic@example.invalid","password":"synthetic-password"}
~~~

In integration mode a successful response retains success/message/userId/name/email and adds:

~~~json
{"accessToken":"nv1_<opaque token>","tokenType":"Bearer","expiresAt":"2026-10-08T20:00:00Z","sessionScope":"FULL"}
~~~

A pending account receives DELETION_ONLY after successful fresh login. This is not an upgrade of a revoked token.

POST /api/account/deletion-request:

~~~json
{"confirm":true}
~~~

HTTP 201 with Cache-Control: no-store:

~~~json
{"status":"PENDING","requestedAt":"2026-10-08T12:00:00Z","scheduledDeletionAt":"2026-10-15T12:00:00Z","cancelledAt":null}
~~~

GET /api/account/deletion-status has no body. HTTP 200 returns the same lifecycle fields, or status NONE with null dates when no request exists. No user ID parameter is used.

POST /api/account/cancel-deletion has no body. Fresh DELETION_ONLY authentication is permitted. HTTP 200 returns CANCELLED and cancelledAt. Cancellation is allowed strictly before the deadline while PENDING; PROCESSING/FAILED/overdue requests cannot be cancelled by this API. After cancellation, log in again for a FULL token.

New guard/deletion errors use {"code":"...","message":"..."} without sensitive details.

| HTTP | Relevant codes |
| --- | --- |
| 400 | CONFIRMATION_REQUIRED, INVALID_REQUEST, INVALID_PERSONAL_ASSET, INVALID_ROUTE |
| 401 | USER_AUTHENTICATION_REQUIRED, SESSION_NOT_VERIFIED, INVALID_CREDENTIALS, RECENT_AUTHENTICATION_REQUIRED |
| 403 | ACCOUNT_PENDING_DELETION, ACCOUNT_OWNERSHIP_REQUIRED, ACCOUNT_BLOCKED |
| 409 | NO_PENDING_DELETION, CANCELLATION_WINDOW_CLOSED, REQUEST_CONFLICT |
| 503 | ACCOUNT_DELETION_DISABLED, INTEGRATION_REQUIRED, ACCOUNT_OPERATION_BUSY, ACCOUNT_INTEGRATION_UNAVAILABLE, PERSONAL_ASSET_UNAVAILABLE |

Existing non-deletion controller business responses and admin JWT error formatting are not globally redesigned.

## Future migration and deployment sequence

These are future instructions, not actions performed in this task.

1. Obtain explicit deployment and data-processing approval. Resolve the client, provider, identity, shared-record and retention blockers first. Verify the intended Cloud SQL instance and take a restorable database backup when cloud operations are authorized.
2. Validate all table/column/foreign-key assumptions in a staging clone with synthetic data. Review duplicate case-insensitive emails and unknown user dependencies.
3. There is no Flyway/Liquibase runner in the existing pom. Deployment does not apply these migrations automatically. Use an approved manual migration process with error-stop and a schema/version ledger.
4. Apply V20261008_01__create_account_deletion_requests.sql if not already recorded, then V20261008_02__account_deletion_integration.sql. Each file has its own BEGIN/COMMIT and lock/statement timeouts; do not wrap it in an incompatible transaction mode or rerun blindly. The new migration creates six tables and one trigger function; it never alters users or existing tables.
5. Keep scheduler-enabled and destructive-enabled false throughout rollout. Keep api-enabled false until all ingress and cancellation tests pass. Do not depend on ddl-auto=update: Phase 1 excludes its entity table from Hibernate DDL; the new JDBC-only tables are not JPA entities and require the manual migration. Before production, use deployment configuration to disable automatic schema mutation and validate mappings against the reviewed schema.
6. Deploy the code only in a later authorized phase. Enable integration on every instance, remove/drain old instances, validate bearer adoption, admin behavior, ownership registration, all route entry points, connection capacity and external writers. New components are under the existing application package and are discovered normally.
7. After a human rollout review, an authorized operator may set enforcement_required=true and rollout_approved_at in the singleton integration-state row. This is a durable deployment attestation, not automatic discovery of all servers. The readiness gate checks it and the actual tables. Restarting this version with integration disabled while that latch is set fails startup. Never roll back to older unguarded code after accepting deletion requests.
8. Enable only the deletion API after separate approval. Validate request, fresh reauthentication, status, cancellation and revocation using test accounts; leave workers off.
9. Review each eligible account's full cleanup plan and provider/retention evidence. Adopt or reconcile legacy objects only through a separately approved procedure with provable ownership. No such migration of real assets is implemented or run here.
10. Only after explicit destructive-processing approval, enable both scheduler-enabled and destructive-enabled with reviewed allowed-buckets. A plan and all checks are still required. Monitor sanitized failure codes and bounded retries. Turning off either worker switch stops new processing; do not remove pending-account enforcement to stop the worker.

Expired session records need an approved retention/pruning job before large-scale rollout; none runs automatically. Do not prune deletion-request epoch history while any earlier session can still be valid.

## Readiness decision

Not production-ready and not a complete automatic provider/shared-data deletion system.

Implemented and locally testable: verified password-user identity, durable token revocation, pending restrictions on inspected synchronous backend routes, cancellation, registered-upload ownership, durable cleanup evidence, safe foreign keys, original seven-day lifecycle/retry/concurrency behavior, and mock-backed worker integration.

Remaining release blockers: client bearer adoption, Google-only reauthentication, real provider ownership/removal integration or verified operator workflow, legacy/unlinked object reconciliation, shared-meeting/retention policy, all-instance rollout approval, production migration/backup/least-privilege review, and cloud/failover/load validation.

Pre-existing authentication debt also remains: legacy passwords are stored/compared as plaintext and the admin JWT uses a hard-coded signing secret. Those mechanisms were not redesigned in this focused integration. A production security review must resolve them through an explicitly planned credential/key migration; this phase must not be advertised as securing the entire application.
