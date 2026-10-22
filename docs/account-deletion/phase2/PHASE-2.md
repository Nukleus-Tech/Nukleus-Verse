# Phase 2 — local adapter preparation, not activation

## Delivery status and safety boundary

Phase 2 adds source and isolated tests only. It does **not** make the deployed/current
application capable of securely identifying an ordinary logged-in user or enforcing
pending-deletion restrictions. Those integration points remain blocked.

All Phase 2 production classes are ordinary, unregistered Java classes. They have no Spring
component/configuration/bean/controller/entity annotations, no scheduler registration, no
initializer that starts a worker, and no new authentication endpoint. The existing Phase 1
rejecting AccountDeletionSafetyGateway remains selected by the current application.

Nothing in Phase 1, User.java, authentication, login, security filters, configuration, pom.xml,
Admin Panel, meeting/avatar code, schema or Unity has been edited. No new dependency or
migration is introduced. No backend application was started. No cloud resource was contacted.

The default Phase2Policy has adaptersEnabled=false and cleanupEnabled=false.
Even an explicitly composed adapter requires the existing Phase 1 scheduler/destructive flags
for cleanup. This is preparation for a later authorized integration, **not production readiness**.

## Implemented adapters

| New class | Responsibility |
| --- | --- |
| EndUserIdentityAdapter | Cross-checks trusted session evidence, immutable user ID, principal, role, expiry, revocation and recent authentication; rejects admin/mixed-role tokens and client identity strings. |
| PendingDeletionAccessAdapter | Rejects ordinary operations for PENDING/PROCESSING/FAILED; permits only verified status/cancellation; restricted sessions never become normal sessions after cancellation. |
| AuthenticatedDeletionFacade | Calls existing request/status/cancel service with the verified user ID only. No user-ID parameter and no new HTTP endpoint. |
| SessionRevocationAdapter | Request-keyed revocation coordination after commit and a mandatory durable write-fence check before cleanup. |
| Phase2SafetyGateway | Implements the existing gateway contract without registering/replacing the rejecting bean. Orders all review/preflight checks around Phase 1 cleanup. |
| PersonalDataInventoryAdapter | Read-only current-user, duplicate-identity, shared-asset, meeting and foreign-key checks. Final OTP/User deletion stays in the existing work store. |
| StorageOwnershipAdapter | Requires complete reviewed inventory, approved bucket/prefix, exact generation and owner metadata; durably journals verified generations before Phase 1 deletes anything. |
| ExternalReferenceCleanupAdapter | Preflights all required providers, invokes reviewed application-scoped cleanup clients and records durable idempotent completion receipts. No real provider client is supplied. |
| ApprovedCleanupPlan | Immutable, request/user/snapshot-bound inventory and review-domain evidence from a trusted durable journal, not a client DTO. |
| CleanupFingerprint | Length-delimited integrity binding for the Phase 1 user snapshot. This is not encryption or anonymization. |
| GcsReference | Canonical comparison of recognized GCS URL forms, including signed URL aliases; never fetches a URL or proves ownership itself. |
| Phase2Ports | Required server-side session, readiness, durable journal and provider-client contracts; recoverable-outage translation into Phase 1 bounded retries. |
| Phase2Policy | Explicit disabled defaults and no-external-work-inside-transaction checks. |

## Authentication findings and actual guarantees

The unchanged AuthController.login returns account information but does not establish a
verified end-user principal/session for these operations. The existing JwtFilter creates
a String principal with ROLE_ADMIN. Meeting/avatar/storage routes bypass that filter.
A submitted userId, email, Google ID, login response, or admin JWT therefore cannot be used
as deletion authority.

EndUserIdentityAdapter deliberately refuses those values. A future verified user flow must
supply the existing AccountPrincipal plus ROLE_USER, and SessionAuthority must independently
resolve a live, non-revoked session for the same immutable users.id and credential-authentication
time. Missing/unavailable evidence fails closed. The adapter verifies the account still exists.
It does not look up identity by client-provided email or inspect a submitted token as trusted claims.

The adapter does not change passwords, authenticate passwords itself, mint JWTs, modify the
SecurityContext, or introduce a parallel login system. Actual logged-in-user identification
remains unavailable until the existing authentication path is securely integrated.

Default recent authentication remains the Phase 1 ten-minute setting. Token refresh is not
reauthentication. A session must still be unexpired for status. Creation/cancellation require
recent verified credentials.

PendingDeletionAccessAdapter accepts trusted operation categories, not user-controlled exemption
headers/paths. For pending accounts it allows DELETION_STATUS and CANCEL_DELETION only.
It never allows ordinary meetings/avatar/upload operations because a deadline has passed.
Revoked credentials are rejected even for status/cancellation: use separately verified restricted
reauthentication. DELETION_ONLY sessions cannot regain ordinary rights after cancellation.

Phase 1 duplicate-request idempotency and its saved deadline remain unchanged. The prospective
Phase 2 facade rejects further request-creation operations while pending; clients should query
status/cancel using restricted authentication. This stricter facade is not currently installed.

SessionRevocationAdapter uses (verified user ID, deletion request ID) as its idempotency key.
It refuses execution inside the request transaction. A delayed callback that observes CANCELLED
does not revoke sessions. A cancellation racing a revocation must never resurrect revoked tokens;
the future authority must reconcile this durably and require fresh ordinary login after cancellation.

## Cleanup ordering and ownership

The prospective Phase2SafetyGateway performs:

1. Require explicit flags and proven integration readiness.
2. Re-read the correct PROCESSING request and passed deadline; require durable cross-instance
   session/write fencing for that exact user/request.
3. Load the immutable approved plan and verify request ID, user ID, current snapshot fingerprint,
   every review domain and unique object identities.
4. Verify all current photo/avatar references map to approved objects. Unknown remote URLs,
   incomplete inventories, arbitrary signed URLs and unreviewed assets block cleanup.
5. Re-read the User snapshot and check shared data. Other-user references and meeting assets
   are compared using canonical GCS identity, not URL string equality. Meeting email membership
   is compared by exact case-insensitive CSV tokens, not substring matching. Case-variant email
   collisions and shared Google/Meshy identifiers block ambiguous cleanup.
6. Reject unknown User foreign-key relationships before external deletion. The only admitted
   FK is the unchanged account_deletion_requests reference with ON DELETE SET NULL.
7. Preflight every required provider before any provider removal or storage delete.
8. Check every approved object generation and owner/exclusive metadata, then durably record all
   verified generation evidence before returning the inventory to the existing Phase 1 GCS cleanup.
9. After Phase 1 storage cleanup, verify approved generations are gone, recheck the snapshot,
   shared-data state and write fence, then remove application-specific external references with
   stable plan IDs and durable completion receipts.
10. The unchanged Phase 1 worker rechecks its claim and removes matching OTP records and the
    User atomically. Audit user identifiers are nulled by its existing FK/completion mechanism.

No external operation runs inside an adapter-owned DB transaction. Neither this module nor
a provider client may hold a DB transaction open across slow cloud calls.

If a pinned generation is absent on retry, it is accepted only if the journal already contains
durable evidence for that exact approved generation. A replacement generation is not targeted.
The journal must survive process crashes and be shared across instances; test-only in-memory
journal implementations are not a production substitute.

Recoverable provider/journal outages can explicitly throw Phase2Ports.RetryableIntegrationFailure.
It extends the existing transient-data failure category, so the unchanged Phase 1 processor
uses its bounded retry count/delay and sanitized CLEANUP_FAILURE code. Ownership/permission/
unknown failures are not automatically made retryable. Providers must support idempotency even
when a provider succeeds but receipt persistence fails.

The GCS ownership contract still requires:

- an explicitly allowed bucket;
- users/{verifiedUserId}/ object names;
- a pinned positive generation;
- matching nukleusUserId metadata and nukleusExclusiveOwner=true;
- complete approved historical inventory, retention review and shared-reference checks.

Existing images/ and images/glb/ uploads lack those guarantees. They remain blocked; there is
no unsafe fallback, URL-based deletion, object renaming or legacy metadata modification.

Unknown references, historical email aliases, backups, logs, retention decisions and indirect
shared links require evidence from the reviewed journal/registry. SQL checks alone do not prove
that every personal datum in every system has been found. Shared meeting records/assets and
administrative records are never deleted, anonymized or rewritten by Phase 2.

## Required later integration — NOT performed

| Blocker | Exact integration responsibility |
| --- | --- |
| No verified ordinary-user session | AuthController/Google login and the existing JwtFilter must establish verified user identity and credential-authentication time, with correct issuer/audience/signature/expiry validation and secure existing key management. Never reinterpret an admin JWT as a user token. |
| Public feature routes bypass authentication | SecurityConfig/JwtFilter and the relevant meeting/avatar/storage/background/realtime ingress must verify account ownership and invoke pending-access checks. Every path must fail closed. |
| No durable revocation backend | Implement SessionAuthority against an approved durable, multi-instance session/refresh-token authority. Coordinate revocation, jobs/in-flight requests and restricted cancellation reauthentication. Do not use a process-local map. |
| No immutable ownership/retry journal | Implement CleanupJournal against an approved durable store and authenticated ownership registry. Schema/store changes require separate authorization; none are made here. Persist generation evidence before destructive work and minimize retained identifiers. |
| Missing global write/reference fencing | ProtectionReadiness must verify actual enforcement, not merely return true. The fence must also prevent other users/jobs from adding shared links, mutating ownership metadata or recreating assets during cleanup. Read-only preflight checks cannot remove this race themselves. |
| Legacy uploads have no trusted owner metadata | StorageController.uploadToGcs/archiveRemoteFile or an approved ownership registry needs authorized integration. Legacy assets require independent ownership review. No existing upload flow is changed. |
| External references are not revocation grants | Provide reviewed Google application-grant and Meshy cleanup clients with proven ownership, scopes, official supported operations, permissions, timeouts and idempotency. A Google subject alone is insufficient. Never delete the person's Google account. |
| Shared/retained data policy | Approve handling of shared meetings, aliases, exports/logs/backups and retention before certifying a complete plan. No blanket cascades or deletes. |
| Binding adapters into the existing module | In a later reviewed composition, register exactly one real gateway with required dependencies and integrate the authenticated facade/access adapter. Verify fallback-bean selection and actual filter ordering in nonproduction; no registration is included now. |

Do not supply no-op production implementations for readiness, session authority or the journal.
All test doubles are scoped to test source and explicitly synthetic.

The read-only reference inventory scans existing URL columns and inspects PostgreSQL FK metadata.
Review production cardinality, query timeouts and an indexed authoritative ownership registry
before deployment. No production scale/performance assertion is made.

## Defaults and preserved behavior

The existing configurable grace period remains exactly 168 hours by default:

    NUKLEUS_ACCOUNTDELETION_GRACEPERIOD=168h

The unchanged API/worker flags all default false:

    NUKLEUS_ACCOUNTDELETION_APIENABLED=false
    NUKLEUS_ACCOUNTDELETION_SCHEDULERENABLED=false
    NUKLEUS_ACCOUNTDELETION_DESTRUCTIVEENABLED=false

Phase 2 supplies no environment-variable activation path, no deployment command and no automatic
bean registration. The policies/adapters are explicitly constructed only in isolated tests.
The existing API contract and migration files are retained unchanged. No production migration
was run and no database schema was added or altered by this delivery.

The existing accountStatus field is not read or written by Phase 2 production code.
Existing login/Admin Panel Block/Unblock must remain independent and upstream during future
integration; cancellation must not bypass administrative blocking.

## Scope of completion

Complete: locally compiled adapter implementations, safety checks, explicit integration ports,
synthetic tests, and blocker documentation.

Incomplete by design: real-user authentication integration, live pending restrictions, durable
revocation/journal implementations, provider-specific cloud cleanup and production wiring.
Deletion stays disabled. This is not an end-to-end operational or production-ready system.

See TEST-RESULTS.md for execution evidence and NEW-FILES.md for the complete file manifest.
