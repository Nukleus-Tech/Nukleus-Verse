# Account deletion: integration-prepared, disabled by default

This is a **new-files-only implementation, not an operational production deletion service**.
The existing application cannot yet authenticate an ordinary end user for these endpoints.
The default safety gateway deliberately refuses request creation and permanent cleanup.
Pending-account access restrictions and session revocation are **not integrated or enforced**.

No existing Java, configuration, dependency, migration, Admin Panel or Unity file is changed.
The deletion lifecycle never reads or writes the existing accountStatus field.
No cloud deployment, billing action, production query, production migration or real-user deletion was performed.

## Implemented components

All production Java additions are under com.nukleus.vrmeeting.accountdeletion, inside the
existing Spring Boot component/entity/repository scan root.

- JPA AccountDeletionRequest entity and Spring Data AccountDeletionRepository.
- Transactional request/status/cancellation service and authenticated-user-only controller.
- Request/response records and controller-scoped JSON exception handling.
- Bound, validated configuration properties and an injectable UTC clock.
- A private, opt-in scheduler (60-second initial delay and fixed delay); no global scheduling changes.
- Multi-instance worker locking, persisted claim tokens, crash recovery and bounded retry state.
- Preflight checks, generation-fenced GCS cleanup, OTP/user deletion and minimal audit completion.
- An explicit, fail-closed integration gateway and a preparatory pending-account access policy.
- Hibernate schema filtering to keep the new table out of automatic DDL.
- Synthetic unit, MVC, compatibility and real isolated PostgreSQL tests.

See NEW-FILES.md for the exact manifest, API.md for the frontend contract,
DEPLOYMENT.md for activation, and TEST-RESULTS.md for executed tests.

## Lifecycle and transaction boundaries

Request creation obtains a pessimistic write lock on the existing user row, looks for the
latest active request and returns its unchanged deadline on duplicates. A partial unique
index is a second independent defense against multiple active requests, including FAILED.
New requests use Instant.now from the UTC clock plus a default Duration of 168 hours.
A deliberately cancelled request can later be replaced by a new request; that is not a duplicate.

Cancellation locks the same user and request, and is allowed only for PENDING strictly
before the deadline. Repeated cancellation of the same CANCELLED request is idempotent.
At the exact deadline, or for PROCESSING/FAILED, cancellation is rejected.

The request transaction commits before the session-revocation integration is called.
If revocation fails, the request remains PENDING and the client sees an error; retrying the
request attempts revocation again without extending the deadline. A future integration MUST
enforce the pending-state database check on each operation, fail closed when unavailable, and
durably finish revocation after crashes. Existing tokens must not silently regain access
after cancellation; require fresh authentication.

Worker sequence:

1. Select a bounded page of due PENDING requests, retryable FAILED requests, and abandoned PROCESSING requests.
2. Acquire a PostgreSQL session advisory lock for the request. A competing worker skips it.
3. In a short transaction, lock user then request, recheck eligibility, increment the attempt
   counter and persist a fresh UUID claim token.
4. In a second short transaction, reject changed identity/assets or unresolved meeting references.
5. Outside any transaction, ask the gateway to confirm complete ownership/retention inventory
   and durable write/session fencing. Delete only approved object generations and invoke
   idempotent provider-specific cleanup.
6. Recheck the advisory connection. In a short final transaction, recheck the claim token,
   user snapshot, deadline and meetings; delete matching email OTPs; mark completion, remove
   the user, and flush atomically.
7. On recoverable storage/DB failures, retain the user and record an allowlisted error code.
   No exception message, URL, credential or email is saved in last_error.

PostgreSQL session locks release after connection loss. Claim tokens prevent an old worker
from committing after another worker recovers a request. Network partitions can still overlap
external calls: object-generation preconditions and idempotent gateway operations are essential.
The retry_count field counts total started attempts, including the initial attempt and crashed
attempts. Default maximum: 5. Delays: retry-delay multiplied by the started-attempt count.
Storage 408/429/5xx and transient SQL errors are retryable. Ownership, retention, unknown errors,
403/404 errors surfaced by the SDK, and integration failures require operator review.
Exhausted FAILED requests remain active/restricted; there is no automatic reset or new admin UI.

The advisory connection is held while external calls run, but it is in autocommit mode:
**no database transaction spans cloud operations**. Use session-preserving DB connections,
not transaction-pooling proxies, and provision at least two available DB connections per worker
plus application capacity. External integrations must set bounded timeouts.
Stopping the scheduler stops new scheduling; allow an in-flight attempt to drain.

## Existing data inventory and boundaries

| Existing data | Classification and implemented treatment |
| --- | --- |
| imageUrl | Personal reference photo locator, not proof of object ownership. Gateway inventory required. |
| avatarUrl, riggedGlbUrl, walkingGlbUrl, idleGlbUrl, sittingGlbUrl | Personal avatar asset locators; may be remote, shared, signed, or retained. Never parsed into unconditional deletes. |
| meshyTaskId | External-service identifier. Removed with local user; provider cleanup is an unimplemented gateway responsibility. |
| googleId | External identity link. Removed with local user. Revoke only this application's grants/sessions when applicable; never delete a person's Google account. |
| currentMeetingId | String link to meeting participation. Nonempty value blocks permanent deletion pending approved cleanup. |
| meetings.host_email / participant_emails | Denormalized strings, no User FK in the source entities. Exact case-insensitive CSV token matching; any match blocks deletion before storage operations. Shared meetings/files are not deleted or rewritten. |
| meetings recording/pdf/notes/ppt URLs | Shared content and possible retention needs. Never deleted by this module. |
| email_otp.email | Matching OTP records deleted only during the final user-deletion transaction. |
| User name/email/password and other scalar fields | Removed with the verified User row, never copied to the deletion audit. |
| Admin entity / accountStatus | Existing administration remains independent and unchanged. No admin cleanup or new panel features. |

Source entities show no additional User foreign-key relationships. This is **not a production
schema inventory**. Unseen database tables, logs, backups, exports, caches and third-party
records require review before enabling cleanup. Unknown restrictive FKs fail deletion and
roll back completion; do not add broad cascading deletes as a shortcut.

Current StorageController writes images/ and images/glb/ objects without authenticated
ownership metadata. Those legacy objects do NOT satisfy this module's safe deletion contract.
The cleanup component accepts only a reviewed OwnedObject inventory with:

- the exact authenticated user ID;
- an explicitly allowed bucket;
- a users/{userId}/ object prefix;
- a positive, pinned generation;
- metadata nukleusUserId matching the user and nukleusExclusiveOwner=true.

A URL in a User row is insufficient. The gateway must check shared references, inventory objects
not currently linked in those six fields, honor retention, and persist/recover a stable inventory
across partial failures. Merely returning an empty list for an unresolved legacy user is unsafe.
The default gateway never does that: it throws INTEGRATION_REQUIRED.

## Required later, explicitly authorized integration

No item below has been implemented in existing files.

1. AuthController login / Google authentication: issue or establish verified **end-user**
   authentication, immutable users.id and trustworthy recent credential-authentication time.
   Current ordinary login returns account data, not a usable user JWT. lastLogin is not proof
   of recent authentication and must not be used as such.
2. JwtFilter.doFilterInternal: stop treating all validated tokens as ROLE_ADMIN for new user
   flows. After verified identity resolution, populate the server-only AccountPrincipal and
   ROLE_USER. Validate issuer/audience/signature/expiry and revocation using the approved
   existing authentication architecture. Do not construct a principal from a submitted ID,
   email, header or unverified token. Preserve admin authentication separately.
3. SecurityConfig.securityFilterChain: require appropriate end-user authentication for
   /api/account/** and the ordinary feature routes; provide consistent JSON authentication
   failures. Existing JwtFilter currently returns plain-text 401 responses before MVC.
   If cookies are introduced, integrate CSRF protection; do not rely on the existing disabled
   CSRF configuration for a cookie-authenticated destructive API.
4. Existing meeting/avatar/storage controllers and relevant ingress/security layer: they
   currently bypass the JWT filter and trust supplied identity fields. Authenticate ownership
   and invoke AccountDeletionAccessPolicy.requireNormalAccess(verifiedUserId) for every ordinary
   operation, including background and realtime paths. Exempt ONLY securely authenticated
   status/cancel and narrowly scoped reauthentication. Do not exempt all /api/account paths.
5. Implement AccountDeletionSafetyGateway as a reviewed Spring bean replacing the rejecting
   fallback. It must check readiness, durably revoke sessions/refresh tokens, stop writes across
   instances, cover in-flight jobs, inventory data, and implement idempotent external cleanup.
   It must not become a no-op approval switch.
6. StorageController uploadToGcs / archiveRemoteFile, or an approved new ownership registry:
   establish authenticated ownership for new objects and review legacy assets. No current
   upload path, bucket, metadata, Cloud IAM or stored object has been changed.
7. Approve a shared-meeting retention/anonymization policy, including historical email aliases.
   Existing meeting records and shared files are intentionally left intact; eligible users
   with such references are blocked until that policy is safely integrated.
8. Verify application-role DB permissions, provider-specific Meshy/Google permissions,
   object retention/soft-delete/versioning behavior, logging/backup retention and recovery policy.
   Test end-to-end with synthetic accounts, revoked sessions and multiple backend instances.

A real future integration must not claim complete deletion merely because User was removed.
Minimal request audit records retain timestamps, lifecycle and retry counts, but the user_id
link is nulled on deletion. They are minimized, not a promise of irreversible anonymization
against separately retained logs/backups. Audit retention and lawful preservation policies
require an explicit operational decision.

PostgreSQL lock behavior follows the [official locking documentation](https://www.postgresql.org/docs/17/explicit-locking.html).
GCS generation fencing follows the [request-preconditions documentation](https://docs.cloud.google.com/storage/docs/request-preconditions).
