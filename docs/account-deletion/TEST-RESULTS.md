# Executed verification

Date: 2026-10-08.

Final local Maven test run: **45 tests, 0 failures, 0 errors, 0 skipped — BUILD SUCCESS**.
All 47 main Java source files (existing application plus the new module) and 6 new test
classes compiled with Java release 21 using the installed JDK 25.0.3.
No existing pom.xml or dependency version was changed.

| Test class | Passed | Coverage |
| --- | ---: | --- |
| AccountDeletionUnitTest | 10 | Seven-day UTC deadline, default safety switches, principal checks, recent auth, lifecycle, schema filter, default gateway, pending policy, existing admin Block/Unblock |
| AccountDeletionControllerTest | 7 | MVC authentication/ownership, confirmation, recent-auth cancellation, status, sanitized errors, rejection of existing admin JWT authentication |
| AccountCleanupServiceTest | 6 | Off switches, owner/bucket/prefix/metadata checks, generation-bound deletion, missing-object idempotency, storage failure, no transaction around cloud operations |
| AccountDeletionProcessorTest | 7 | Disabled batch, retry classification, review-required failures, competing workers, DB-before-cloud ordering, preflight rejection, sanitized stored errors |
| AccountDeletionConfigurationTest | 2 | Actual documented environment-variable binding and isolated Hibernate DDL customization |
| AccountDeletionPostgresTest | 13 | Real migration, concurrent creation, partial unique index/FK, cancellation boundaries, due selection, bounded retries, session advisory locks, atomic deletion, audit unlinking, stale-token fencing, shared-meeting blocking and unexpected-FK rollback |

## Isolation and actual execution

Tests ran in a copied project under the Codex visualization workspace; the actual backend's
existing source/configuration/build outputs were not edited by the build.
No Spring Boot application context was started, and application.properties/.env were not loaded
as test connection configuration. GCS and provider integrations were mocks.

A new local PostgreSQL 17.10 cluster was initialized solely for this task, bound to
127.0.0.1:55439. The database was named nukleus_deletion_test. Each integration run created its
own random deletion_test_<UUID> schema, including entirely synthetic users/meetings/OTP/admin
tables; the schema was dropped after the tests. No existing database was used.
The canary test verifies migration leaves an existing synthetic user unchanged.
Expected foreign-key violation log output belongs to the synthetic rollback test, not a failing test.

Unit tests first required Maven to download the missing Surefire JUnit provider. Earlier runs
caught and corrected test-fixture issues (Hibernate Table constructor and environment-source
naming). The final result above supersedes those development runs.

Runtime warnings remain from existing/transitive Commons Logging and Mockito dynamic-agent
attachment on JDK 25; no source compilation errors remain. No dependency/configuration files
were changed to suppress those warnings.

## Repeat safely

Unit/MVC tests run with the existing Maven wrapper:

    .\mvnw.cmd test

The PostgreSQL suite skips unless explicitly opted in. To include it, first provision an
entirely disposable local database named nukleus_deletion_test, owned by a test-only role,
and then use PowerShell:

    $env:ACCOUNT_DELETION_TEST_OPT_IN = 'I_UNDERSTAND_LOCAL_SYNTHETIC_ONLY'
    $env:ACCOUNT_DELETION_TEST_JDBC_URL = 'jdbc:postgresql://127.0.0.1:55439/nukleus_deletion_test'
    $env:ACCOUNT_DELETION_TEST_DB_USER = 'deletion_test'
    .\mvnw.cmd test

If needed, set ACCOUNT_DELETION_TEST_DB_PASSWORD through a secure local mechanism.
The test rejects non-loopback URLs, alternate database names and URL options. Do not port-forward
a production database onto this port. The explicit opt-in is an assertion that the target is a
disposable synthetic-only database. Each run removes only its newly created random schema.

## Not executed / not yet proven

- Production or Cloud SQL migrations, billing actions, deployment or real GCS operations.
- End-to-end user authentication, revocation, restricted cancellation login or feature blocking.
- Legacy-object ownership migration, provider-specific Google/Meshy cleanup, shared-meeting
  anonymization, production retention/backups, actual Cloud IAM or network-partition fault injection.
- Full UI/Admin Panel browser regression or Unity/Quest/mobile testing.

The direct existing AdminUserController Block/Unblock test passes, and existing files are
protected by before/after SHA-256 verification. This is not a claim of exhaustive UI regression.
Production activation remains blocked on the integrations listed in IMPLEMENTATION.md.
