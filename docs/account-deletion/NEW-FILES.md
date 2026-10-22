# New backend files

Exactly 30 files are added to the existing backend. No pre-existing file is replaced.
Build/test copies and the disposable local PostgreSQL cluster reside outside this backend and
are not application deliverables. Package additions use existing dependencies only.

- `docs/account-deletion/API.md`
- `docs/account-deletion/DEPLOYMENT.md`
- `docs/account-deletion/IMPLEMENTATION.md`
- `docs/account-deletion/NEW-FILES.md`
- `docs/account-deletion/TEST-RESULTS.md`
- `src/main/java/com/nukleus/vrmeeting/accountdeletion/AccountCleanupService.java`
- `src/main/java/com/nukleus/vrmeeting/accountdeletion/AccountDeletionAccessPolicy.java`
- `src/main/java/com/nukleus/vrmeeting/accountdeletion/AccountDeletionConfiguration.java`
- `src/main/java/com/nukleus/vrmeeting/accountdeletion/AccountDeletionController.java`
- `src/main/java/com/nukleus/vrmeeting/accountdeletion/AccountDeletionDtos.java`
- `src/main/java/com/nukleus/vrmeeting/accountdeletion/AccountDeletionException.java`
- `src/main/java/com/nukleus/vrmeeting/accountdeletion/AccountDeletionExceptionHandler.java`
- `src/main/java/com/nukleus/vrmeeting/accountdeletion/AccountDeletionProcessor.java`
- `src/main/java/com/nukleus/vrmeeting/accountdeletion/AccountDeletionProperties.java`
- `src/main/java/com/nukleus/vrmeeting/accountdeletion/AccountDeletionRepository.java`
- `src/main/java/com/nukleus/vrmeeting/accountdeletion/AccountDeletionRequest.java`
- `src/main/java/com/nukleus/vrmeeting/accountdeletion/AccountDeletionSafetyGateway.java`
- `src/main/java/com/nukleus/vrmeeting/accountdeletion/AccountDeletionScheduler.java`
- `src/main/java/com/nukleus/vrmeeting/accountdeletion/AccountDeletionSchemaFilter.java`
- `src/main/java/com/nukleus/vrmeeting/accountdeletion/AccountDeletionService.java`
- `src/main/java/com/nukleus/vrmeeting/accountdeletion/AccountDeletionWorkStore.java`
- `src/main/java/com/nukleus/vrmeeting/accountdeletion/AccountDeletionWorkerLock.java`
- `src/main/java/com/nukleus/vrmeeting/accountdeletion/AuthenticatedAccountResolver.java`
- `src/main/resources/db/account-deletion/V20261008_01__create_account_deletion_requests.sql`
- `src/test/java/com/nukleus/vrmeeting/accountdeletion/AccountCleanupServiceTest.java`
- `src/test/java/com/nukleus/vrmeeting/accountdeletion/AccountDeletionConfigurationTest.java`
- `src/test/java/com/nukleus/vrmeeting/accountdeletion/AccountDeletionControllerTest.java`
- `src/test/java/com/nukleus/vrmeeting/accountdeletion/AccountDeletionPostgresTest.java`
- `src/test/java/com/nukleus/vrmeeting/accountdeletion/AccountDeletionProcessorTest.java`
- `src/test/java/com/nukleus/vrmeeting/accountdeletion/AccountDeletionUnitTest.java`
