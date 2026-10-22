# Account deletion API contract

These endpoints are implemented and locally tested, but disabled by default.
The current deployment and ownership prerequisites are in COMPLETION.md.
The historical Phase 1 and Phase 2 notes describe earlier limitations, not the current integration.

No Unity/UI changes are included.

## Authentication

Use the integrated, verified end-user authentication over HTTPS when integration is enabled.
An administrative token is not authorization to delete an end-user account.
No endpoint accepts an identity selector; IDs/emails in query parameters never select an account.
The authenticated server principal must carry users.id and ROLE_USER.

Creation and cancellation require credential reauthentication within 10 minutes by default.
Refreshing an old token does not count as reauthentication. Status permits an otherwise valid
older session, subject to durable revocation and restricted-session checks on every request.

With integration enabled, POST /api/auth/login accepts the existing email/password body and
adds accessToken, tokenType, expiresAt and sessionScope to the existing successful response.
Use Authorization: Bearer <accessToken> on all user application routes.
Tokens have an opaque nv1_ prefix; admin JWTs and client user IDs are not accepted.
After requesting deletion, old tokens are invalid. Fresh login returns DELETION_ONLY,
permitting status and cancellation but not ordinary application access.
After cancellation, log in again for FULL access; old tokens remain invalid.

### Google only accounts

POST /api/auth/google-challenge with no body returns HTTP 200:

    {"nonce":"<one-time random nonce>","expiresAt":"2026-10-08T12:05:00Z"}

Pass that nonce to the Google ID-token sign-in request. Then POST /api/auth/google:

    {"idToken":"<signed Google ID token containing that nonce>"}

The backend verifies the signature, issuer, configured audience, email_verified, expiry,
recent issued-at time and one-time nonce. It identifies an existing user only through the
signed sub matching users.google_id. Email equality never links an account; there is no
new Google account-registration or email-linking flow.
Successful responses have the same token fields as password login. Obtain a new challenge
and fresh Google token to reauthenticate a pending account. Challenges expire in five
minutes and cannot be reused; a nonce is not an authentication credential by itself.
These two routes are unavailable while integration is disabled.

## Create

POST /api/account/deletion-request

Authorization: Bearer <verified-end-user-token>
Content-Type: application/json

Request:

    {"confirm": true}

201 Created on a new request; 200 OK on an already active request (same deadline):

    {
      "status": "PENDING",
      "requestedAt": "2026-10-08T12:00:00Z",
      "scheduledDeletionAt": "2026-10-15T12:00:00Z",
      "cancelledAt": null
    }

The example is exactly 168 elapsed hours. Timestamps use UTC/ISO-8601.
Do not send userId, email, a requested deadline or a replacement prompt.
If a revocation integration fails after persistence, an error response may coexist with a
PENDING request; query status/retry creation rather than assuming the request was rolled back.

## Status

GET /api/account/deletion-status

Authorization: Bearer <verified-end-user-or-restricted-session-token>

200 OK with the same response shape. Before any request:

    {
      "status": "NONE",
      "requestedAt": null,
      "scheduledDeletionAt": null,
      "cancelledAt": null
    }

Lifecycle values are PENDING, CANCELLED, PROCESSING, COMPLETED and FAILED.
NONE is only an API response for no request. A deleted user cannot normally authenticate
to query COMPLETED; completion auditing is internal and does not expose a user lookup API.

## Cancel

POST /api/account/cancel-deletion

Authorization: Bearer <recently-reauthenticated-end-user-or-restricted-session-token>

No request body is required. 200 OK before the deadline:

    {
      "status": "CANCELLED",
      "requestedAt": "2026-10-08T12:00:00Z",
      "scheduledDeletionAt": "2026-10-15T12:00:00Z",
      "cancelledAt": "2026-10-09T10:00:00Z"
    }

Repeated cancellation is idempotent for the most recent CANCELLED request.
Cancellation exactly at or after scheduledDeletionAt is rejected.
Cancellation does not unblock an administratively blocked user or restore revoked credentials.

## Errors

Controller-originated responses use Cache-Control: no-store and:

    {"code":"CANCELLATION_WINDOW_CLOSED","message":"CANCELLATION_WINDOW_CLOSED"}

| HTTP | Code | Meaning |
| --- | --- | --- |
| 400 | INVALID_REQUEST / CONFIRMATION_REQUIRED | Invalid JSON, validation failure, or no affirmative confirmation. |
| 401 | AUTHENTICATION_REQUIRED | No authenticated principal. |
| 401 | RECENT_AUTHENTICATION_REQUIRED | Reauthenticate before creating/cancelling. |
| 403 | USER_AUTHENTICATION_REQUIRED | Wrong principal/role, including existing admin JWTs. |
| 403 | ACCOUNT_PENDING_DELETION | Restricted session or pending account attempted normal application access. |
| 404 | ACCOUNT_NOT_FOUND | Authenticated account no longer exists. |
| 409 | NO_PENDING_DELETION | No request to cancel. |
| 409 | CANCELLATION_WINDOW_CLOSED | Deadline passed or processing/failed lifecycle. |
| 409 | REQUEST_CONFLICT | Database integrity conflict; reload status. |
| 503 | ACCOUNT_DELETION_DISABLED | API flag is off. |
| 503 | INTEGRATION_REQUIRED | Required safety/revocation integration is absent. |
| 503 | ACCOUNT_DELETION_UNAVAILABLE | Unexpected backend failure; no sensitive details returned. |

Integrated user routes return consistent JSON authentication/guard errors before controllers.
Additional errors are 401 USER_AUTHENTICATION_REQUIRED, SESSION_NOT_VERIFIED,
INVALID_CREDENTIALS, GOOGLE_REAUTHENTICATION_REQUIRED and GOOGLE_ACCOUNT_NOT_LINKED;
403 ACCOUNT_BLOCKED or ACCOUNT_OWNERSHIP_REQUIRED; 429 GOOGLE_CHALLENGE_LIMIT; and
503 ACCOUNT_OPERATION_BUSY or ACCOUNT_INTEGRATION_UNAVAILABLE. Retry busy operations with
backoff; do not treat a transport failure as cancellation or extend the original deadline.
Existing admin JWT error formatting and ordinary controller business responses are unchanged.

No password, JWT, signed storage URL, Google ID, Meshy task ID or internal error message is
returned by the deletion controller. Do not place credentials in query strings.
