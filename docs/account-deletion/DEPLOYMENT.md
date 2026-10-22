# Deployment runbook — preparation only, do not activate now

Billing is disabled. No action in this document was executed against Google Cloud.
This runbook is not authorization to enable billing, change resources or delete real data.

## Migration mechanism actually present

The existing pom.xml has no Flyway or Liquibase dependency; no prior SQL migration mechanism
was found. The application currently uses spring.jpa.hibernate.ddl-auto=update.
Adding a versioned SQL filename does NOT make it run automatically.

New migration:

    src/main/resources/db/account-deletion/V20261008_01__create_account_deletion_requests.sql

It explicitly creates account_deletion_requests, all required columns, two worker columns
(next_attempt_at and claim_token), status/transition-shape checks, a partial active-user unique
index, and due/history indexes. All times are timestamptz; Java uses Instant.
user_id is nullable bigint, referencing users(id) ON DELETE SET NULL.
No ALTER/UPDATE/DELETE of users is contained in the migration. Existing users stay unchanged.
A deleted user's older audit requests lose their user identifier automatically.
Production table names/types/schema must still be verified against the real DB later.

The new HibernatePropertiesCustomizer adds a SchemaFilterProvider excluding ONLY this new
table from automatic create/update/drop/truncate. Existing tables retain their existing DDL
behavior. An existing custom schema-filter provider would require explicit composition and
causes startup to fail rather than silently replacing it. Explicit Hibernate validation still
includes the deletion table. This filter was exercised using Hibernate 6.6.18.Final and
ddl-auto=update against isolated PostgreSQL 17.10.

No migration history table or production migration runner is silently introduced.
Use a DBA-controlled manual migration process with recorded version/checksum, or add an
approved migration framework during a later authorized integration phase.

## Configuration (no existing properties/YAML changes)

Prefix: nukleus.account-deletion

| Property suffix | Environment variable | Default |
| --- | --- | --- |
| api-enabled | NUKLEUS_ACCOUNTDELETION_APIENABLED | false |
| scheduler-enabled | NUKLEUS_ACCOUNTDELETION_SCHEDULERENABLED | false |
| destructive-enabled | NUKLEUS_ACCOUNTDELETION_DESTRUCTIVEENABLED | false |
| grace-period | NUKLEUS_ACCOUNTDELETION_GRACEPERIOD | 168h |
| recent-authentication | NUKLEUS_ACCOUNTDELETION_RECENTAUTHENTICATION | 10m |
| retry-delay | NUKLEUS_ACCOUNTDELETION_RETRYDELAY | 15m |
| max-attempts | NUKLEUS_ACCOUNTDELETION_MAXATTEMPTS | 5 (1–10) |
| batch-size | NUKLEUS_ACCOUNTDELETION_BATCHSIZE | 10 (1–100) |
| allowed-buckets | NUKLEUS_ACCOUNTDELETION_ALLOWEDBUCKETS | empty |

Environment spelling deliberately removes property hyphens, following Spring Boot binding.
The exact spellings above are tested. Durations must be positive; changing the grace period
affects NEW requests only. Existing deadlines are not recalculated.
Use synchronized server clocks and retain the seven-day production policy unless explicitly approved.

For example, future environment configuration may set:

    NUKLEUS_ACCOUNTDELETION_GRACEPERIOD=168h
    NUKLEUS_ACCOUNTDELETION_APIENABLED=false
    NUKLEUS_ACCOUNTDELETION_SCHEDULERENABLED=false
    NUKLEUS_ACCOUNTDELETION_DESTRUCTIVEENABLED=false

All three switches remain false in the delivered code.
The scheduler requires BOTH scheduler-enabled and destructive-enabled. Other destructive
components independently check those two switches. The API switch does not bypass the
rejecting gateway or missing authentication. Do not set flags alone and call this integrated.

See [Spring Boot external configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html)
for the binding conventions.

## Later Cloud SQL activation, with explicit approval

1. The owner must separately authorize any future billing/deployment work. Verify the intended
   existing project, Cloud SQL instance, region, database, PostgreSQL version, database owner,
   users.id bigint PK, table/column names, existing FKs, RLS, and network route. Do not create
   a replacement instance as an assumption. Do not put credentials in shell history.
2. Take an approved on-demand database backup, confirm completion and record its identifier.
   Test the agreed restore procedure in an authorized nonproduction environment. Google documents
   [Cloud SQL backup options](https://docs.cloud.google.com/sql/docs/postgres/backup-recovery/backups);
   choose the process applicable to this instance rather than assuming a backup already exists.
3. Review the exact SQL checksum, indexes, expected empty new table and migration permissions.
   Test the same migration on a sanitized staging copy first. Record baseline users row counts
   and the approved integrity check without exporting personal data.
4. With application deletion flags OFF, an authorized DBA selects the exact target database
   and trusted schema/search_path and runs the file once. Example command shape, not executed:

       psql "service=<approved-reviewed-database-service>" -X -v ON_ERROR_STOP=1 -f src/main/resources/db/account-deletion/V20261008_01__create_account_deletion_requests.sql

   The file owns BEGIN/COMMIT and sets lock/statement timeouts. Do not add a conflicting
   outer transaction. A repeated application deliberately errors on existing objects; inspect
   recorded version/checksum and actual schema, never drop an existing table to force it through.
   On any error stop, check rollback and review before retry.
5. Verify the FK's ON DELETE SET NULL action, partial unique index, due/history indexes,
   timestamp types and checks. Verify users remain unchanged by the migration. Record success
   and checksum in the deployment change record. Give the runtime role only required privileges:
   request-table read/insert/update and identity-sequence usage; existing user/OTP delete only
   for approved permanent cleanup; meeting read. Separate migration DDL permissions from runtime.
6. Deploy the new files with all flags OFF. They are beneath the existing scan root; no edit
   to VrmeetingApplication is needed. Do not launch an ad-hoc test application pointed at Cloud
   SQL, because the EXISTING ddl-auto=update can still alter other existing tables.
   Establish an approved validation-only production DDL policy separately; this task does not
   change the existing property or silently change existing-table behavior.
7. Complete every authentication, ownership, session, retention and external-cleanup integration
   in IMPLEMENTATION.md under separate authorization. Verify the real security filter chain,
   unauthorized/other-user attempts, session revocation, cancellation access, in-flight jobs,
   legacy assets, shared meetings and multiple deployed instances. Add bounded SDK timeouts.
   Use direct/session-preserving DB connections for advisory locks.
8. In approved staging only, install a genuine safety gateway, set the bucket allowlist, and
   enable api-enabled. Validate with synthetic users, keeping destructive flags OFF initially.
   Then test BOTH worker flags with synthetic assets and the same schema/permissions as production.
   Do not shorten real users' saved grace periods to accelerate validation.
9. Obtain explicit destructive-operation approval. Only then configure the reviewed allowlist,
   api-enabled=true, scheduler-enabled=true and destructive-enabled=true in the production
   deployment environment and restart/redeploy through the approved process. The scheduler starts
   after 60 seconds and repeats 60 seconds after each batch completes. No GCP Cloud Scheduler
   resource is needed or created by this implementation.
10. Monitor request states, failed/exhausted attempts, oldest due time, orphan requests, connection
    pool capacity and integration readiness without logging emails, URLs or credentials.
    Alert operators on terminal FAILED requests; do not repeatedly reset retry_count automatically.
    Approve a minimal audit-retention policy and provider backup/soft-delete retention handling.

Emergency stop: turn scheduler-enabled and destructive-enabled off and restart/drain the worker
under the deployment procedure. Keep pending-account restrictions and status/cancellation access
working. Disabling api-enabled also disables cancellation/status at the service layer, so do not
use it as the normal worker-only kill switch. An in-flight irreversible external deletion cannot
be rolled back by switching flags; shutdown/rollback plans must account for partial cleanup.
Do not drop the audit table or restore personal data automatically as a rollback mechanism.
