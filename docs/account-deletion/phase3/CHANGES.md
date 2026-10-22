# Account deletion Phase 3 change manifest

Backend root: C:/Users/Rushil/OneDrive/Desktop/NukleusVerseBackend/vrmeeting

Exactly eight existing source files are changed. Supporting implementation, migration, tests and documentation are new. No files are deleted, renamed or moved.

## Existing files modified

1. [src/main/java/com/nukleus/vrmeeting/security/JwtFilter.java](C:/Users/Rushil/OneDrive/Desktop/NukleusVerseBackend/vrmeeting/src/main/java/com/nukleus/vrmeeting/security/JwtFilter.java): Invoke the integration adapter inside the existing filter; retain the legacy admin path.
2. [src/main/java/com/nukleus/vrmeeting/security/SecurityConfig.java](C:/Users/Rushil/OneDrive/Desktop/NukleusVerseBackend/vrmeeting/src/main/java/com/nukleus/vrmeeting/security/SecurityConfig.java): Require ROLE_ADMIN for admin routes, not merely any authenticated principal.
3. [src/main/java/com/nukleus/vrmeeting/controller/AuthController.java](C:/Users/Rushil/OneDrive/Desktop/NukleusVerseBackend/vrmeeting/src/main/java/com/nukleus/vrmeeting/controller/AuthController.java): Add verified-session login and registration ownership validation hooks.
4. [src/main/java/com/nukleus/vrmeeting/controller/MeetingController.java](C:/Users/Rushil/OneDrive/Desktop/NukleusVerseBackend/vrmeeting/src/main/java/com/nukleus/vrmeeting/controller/MeetingController.java): Add verified owner/host checks and prevent publishing personal objects as shared files.
5. [src/main/java/com/nukleus/vrmeeting/controller/AvatarController.java](C:/Users/Rushil/OneDrive/Desktop/NukleusVerseBackend/vrmeeting/src/main/java/com/nukleus/vrmeeting/controller/AvatarController.java): Require the verified owner and registered personal-object references for avatar writes.
6. [src/main/java/com/nukleus/vrmeeting/controller/StorageController.java](C:/Users/Rushil/OneDrive/Desktop/NukleusVerseBackend/vrmeeting/src/main/java/com/nukleus/vrmeeting/controller/StorageController.java): Reuse the existing injected Storage bean; route new personal uploads through ownership recording; check meeting host.
7. [src/main/java/com/nukleus/vrmeeting/accountdeletion/AccountDeletionProcessor.java](C:/Users/Rushil/OneDrive/Desktop/NukleusVerseBackend/vrmeeting/src/main/java/com/nukleus/vrmeeting/accountdeletion/AccountDeletionProcessor.java): Acquire/check the shared ingress exclusion fence around the existing worker attempt.
8. [src/main/java/com/nukleus/vrmeeting/accountdeletion/phase2/PersonalDataInventoryAdapter.java](C:/Users/Rushil/OneDrive/Desktop/NukleusVerseBackend/vrmeeting/src/main/java/com/nukleus/vrmeeting/accountdeletion/phase2/PersonalDataInventoryAdapter.java): Recognize only the three new reviewed cascading user_id foreign keys; continue rejecting unknown dependencies.

The reviewed existing-file diff contains 70 inserted lines and 4 deleted lines. Original newline styles and final-newline conventions were preserved. Phase 1 lifecycle, deadline/cancellation logic and retry state transitions were reused, not replaced.

## New backend files

- [src/main/resources/db/account-deletion/V20261008_02__account_deletion_integration.sql](C:/Users/Rushil/OneDrive/Desktop/NukleusVerseBackend/vrmeeting/src/main/resources/db/account-deletion/V20261008_02__account_deletion_integration.sql)
- [src/main/java/com/nukleus/vrmeeting/accountdeletion/phase3/Phase3Properties.java](C:/Users/Rushil/OneDrive/Desktop/NukleusVerseBackend/vrmeeting/src/main/java/com/nukleus/vrmeeting/accountdeletion/phase3/Phase3Properties.java)
- [src/main/java/com/nukleus/vrmeeting/accountdeletion/phase3/OperationFence.java](C:/Users/Rushil/OneDrive/Desktop/NukleusVerseBackend/vrmeeting/src/main/java/com/nukleus/vrmeeting/accountdeletion/phase3/OperationFence.java)
- [src/main/java/com/nukleus/vrmeeting/accountdeletion/phase3/JdbcSessionAuthority.java](C:/Users/Rushil/OneDrive/Desktop/NukleusVerseBackend/vrmeeting/src/main/java/com/nukleus/vrmeeting/accountdeletion/phase3/JdbcSessionAuthority.java)
- [src/main/java/com/nukleus/vrmeeting/accountdeletion/phase3/JdbcCleanupJournal.java](C:/Users/Rushil/OneDrive/Desktop/NukleusVerseBackend/vrmeeting/src/main/java/com/nukleus/vrmeeting/accountdeletion/phase3/JdbcCleanupJournal.java)
- [src/main/java/com/nukleus/vrmeeting/accountdeletion/phase3/IntegrationReadiness.java](C:/Users/Rushil/OneDrive/Desktop/NukleusVerseBackend/vrmeeting/src/main/java/com/nukleus/vrmeeting/accountdeletion/phase3/IntegrationReadiness.java)
- [src/main/java/com/nukleus/vrmeeting/accountdeletion/phase3/IntegrationRoutes.java](C:/Users/Rushil/OneDrive/Desktop/NukleusVerseBackend/vrmeeting/src/main/java/com/nukleus/vrmeeting/accountdeletion/phase3/IntegrationRoutes.java)
- [src/main/java/com/nukleus/vrmeeting/accountdeletion/phase3/AuthenticatedRouteGuard.java](C:/Users/Rushil/OneDrive/Desktop/NukleusVerseBackend/vrmeeting/src/main/java/com/nukleus/vrmeeting/accountdeletion/phase3/AuthenticatedRouteGuard.java)
- [src/main/java/com/nukleus/vrmeeting/accountdeletion/phase3/IntegrationExceptionHandler.java](C:/Users/Rushil/OneDrive/Desktop/NukleusVerseBackend/vrmeeting/src/main/java/com/nukleus/vrmeeting/accountdeletion/phase3/IntegrationExceptionHandler.java)
- [src/main/java/com/nukleus/vrmeeting/accountdeletion/phase3/OwnedUploadRegistry.java](C:/Users/Rushil/OneDrive/Desktop/NukleusVerseBackend/vrmeeting/src/main/java/com/nukleus/vrmeeting/accountdeletion/phase3/OwnedUploadRegistry.java)
- [src/main/java/com/nukleus/vrmeeting/accountdeletion/phase3/Phase3Configuration.java](C:/Users/Rushil/OneDrive/Desktop/NukleusVerseBackend/vrmeeting/src/main/java/com/nukleus/vrmeeting/accountdeletion/phase3/Phase3Configuration.java)
- [tools/account-deletion/Run-Phase3Tests.ps1](C:/Users/Rushil/OneDrive/Desktop/NukleusVerseBackend/vrmeeting/tools/account-deletion/Run-Phase3Tests.ps1)
- [src/test/java/com/nukleus/vrmeeting/accountdeletion/Phase3IntegrationTest.java](C:/Users/Rushil/OneDrive/Desktop/NukleusVerseBackend/vrmeeting/src/test/java/com/nukleus/vrmeeting/accountdeletion/Phase3IntegrationTest.java)
- [docs/account-deletion/phase3/PHASE-3.md](C:/Users/Rushil/OneDrive/Desktop/NukleusVerseBackend/vrmeeting/docs/account-deletion/phase3/PHASE-3.md)
- [docs/account-deletion/phase3/TEST-RESULTS.md](C:/Users/Rushil/OneDrive/Desktop/NukleusVerseBackend/vrmeeting/docs/account-deletion/phase3/TEST-RESULTS.md)
- [docs/account-deletion/phase3/CHANGES.md](C:/Users/Rushil/OneDrive/Desktop/NukleusVerseBackend/vrmeeting/docs/account-deletion/phase3/CHANGES.md)

## Checkpoints and verification

Before editing, a SHA-256 baseline was captured for all 415 existing backend files, including existing build and Git metadata. All 415 still matched immediately before installation.

The original bytes of the eight modified files are retained outside the backend under:

C:/Users/Rushil/.codex/visualizations/2026/10/07/01a114d9-c5f0-7d02-a03e-bb569f983d6c/account-deletion-phase3/phase3-checkpoint/

The staging copy, local test clusters, Surefire reports, original baseline and installation verification files remain under:

C:/Users/Rushil/.codex/visualizations/2026/10/07/01a114d9-c5f0-7d02-a03e-bb569f983d6c/account-deletion-phase3

They are audit/build artifacts, not additional backend source changes. Test output and checkpoints are not copied into the backend. Nothing has been committed to Git.

The installation verifies every original hash before writing, refuses to overwrite any proposed new file, copies only this allowlist and compares each destination with its tested staged source. All other original files must still match the baseline after installation.

## Existing file hashes

### JwtFilter.java

Before: B5A72955326311C553AA78FF4652833AC339432C5EF5A5BE50685EDD2DBA3931

After: B3C2D29D65179B537E9EE70C0072F90DB0E086609B9D28BB68B3642191D211C7

### SecurityConfig.java

Before: D5A6DA7308E7E995A4AB28AAA71596B87B24B6106BA6CF993AAB80062FEE009D

After: A2C722E2536917613B5BFE5B62AE6B49BAE8FD6487EAE718E0E4D97679DC8F5C

### AuthController.java

Before: E6926A7038243920AD3F6E870D75709D4BD458718DCAC4C0DEA5233DC5B49B52

After: AC4AB6C12C526652313AB5F8715C795FA424CFC15598960741E01F4E81BCDEEE

### MeetingController.java

Before: E2F70FD64A4173712370A189C6D01EB8574E0C288373157035E4CD0345BBBAA0

After: 7C881A946D5B2E7463EA0B412D28613DBE6120FE80567754FB66913D5E3511A4

### AvatarController.java

Before: 5B0F230B0B35BF1354F9C9C804466B0847D39D0EB1E909F246BCE099B342B864

After: 4730EE56CDB33C0969C06DA5BCAC378103E370D37F4A99B082CB4C9AC82B4943

### StorageController.java

Before: F2C7B20562DA07F3CD92D58A5B8C05A4F4D58063077C4C9EED81AC59FC652DD8

After: 0E51B8DC4F5C4A387C31A7800DFE0EBA22FB418DC93FDCAFA591648436ED039B

### AccountDeletionProcessor.java

Before: 783F6FEE3697EBB651BB7A439B2E0F984F6EA2A9528616850134C9FCE8EDACFF

After: B43B4F3B1D27768C6BF44C73253283B22AB1294729EB6C65D5D8821AC92556B6

### PersonalDataInventoryAdapter.java

Before: E9C32BEE370C2DF92EA03DEF2421D60B6F05F081647D7D3D36DF6FB348E6AFDE

After: 038791568CD8DA51456E76D2C0443DB105EB7EA609D78726F0B12EBFB2C6E00E

## Preserved scope

User.java, UserRepository.java, every Admin Panel controller, the existing accountStatus lifecycle, pom.xml, application.properties, existing SQL migrations, and all existing tests remain byte-for-byte unchanged. No Unity/UI files were edited.

No deployment, backend application launch, production database migration, Google Cloud billing/resource access, real account deletion or real storage deletion was performed. Integration/API/scheduler/destructive flags remain false by default.

See PHASE-3.md for explicit remaining blockers. The result is not production-ready or a complete live provider-cleanup implementation.

## Generated build artifacts observed after installation

The installer initially verified 407 original files unchanged. A subsequent audit found 17 new target/ artifacts and eight updated existing class files. No build was run in the original backend by this task and no target/ output was copied there; these changes are consistent with a background editor build. They were not deleted or overwritten. All 302 non-target original files outside the eight approved source changes still match their original hashes.

These generated outputs are separate from the eight modified source files and sixteen new source/documentation/tool files listed above.

Updated existing generated files:

- target/classes/com/nukleus/vrmeeting/accountdeletion/AccountDeletionProcessor.class
- target/classes/com/nukleus/vrmeeting/accountdeletion/phase2/PersonalDataInventoryAdapter.class
- target/classes/com/nukleus/vrmeeting/controller/AuthController.class
- target/classes/com/nukleus/vrmeeting/controller/AvatarController.class
- target/classes/com/nukleus/vrmeeting/controller/MeetingController.class
- target/classes/com/nukleus/vrmeeting/controller/StorageController.class
- target/classes/com/nukleus/vrmeeting/security/JwtFilter.class
- target/classes/com/nukleus/vrmeeting/security/SecurityConfig.class

New generated files:

- target/classes/com/nukleus/vrmeeting/accountdeletion/phase3/AuthenticatedRouteGuard.class
- target/classes/com/nukleus/vrmeeting/accountdeletion/phase3/IntegrationExceptionHandler.class
- target/classes/com/nukleus/vrmeeting/accountdeletion/phase3/IntegrationReadiness.class
- target/classes/com/nukleus/vrmeeting/accountdeletion/phase3/IntegrationRoutes.class
- target/classes/com/nukleus/vrmeeting/accountdeletion/phase3/IntegrationRoutes$LegacyFilter.class
- target/classes/com/nukleus/vrmeeting/accountdeletion/phase3/JdbcCleanupJournal.class
- target/classes/com/nukleus/vrmeeting/accountdeletion/phase3/JdbcSessionAuthority.class
- target/classes/com/nukleus/vrmeeting/accountdeletion/phase3/JdbcSessionAuthority$Evidence.class
- target/classes/com/nukleus/vrmeeting/accountdeletion/phase3/OperationFence.class
- target/classes/com/nukleus/vrmeeting/accountdeletion/phase3/OperationFence$Lease.class
- target/classes/com/nukleus/vrmeeting/accountdeletion/phase3/OwnedUploadRegistry.class
- target/classes/com/nukleus/vrmeeting/accountdeletion/phase3/Phase3Configuration.class
- target/classes/com/nukleus/vrmeeting/accountdeletion/phase3/Phase3Properties.class
- target/classes/db/account-deletion/V20261008_02__account_deletion_integration.sql
- target/test-classes/com/nukleus/vrmeeting/accountdeletion/Phase3IntegrationTest.class
- target/test-classes/com/nukleus/vrmeeting/accountdeletion/Phase3IntegrationTest$MutableClock.class
- target/test-classes/com/nukleus/vrmeeting/accountdeletion/Phase3IntegrationTest$WebConfiguration.class

