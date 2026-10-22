package com.nukleus.vrmeeting.accountdeletion;

import com.google.cloud.storage.StorageException;
import org.junit.jupiter.api.*;
import java.util.*;
import static com.nukleus.vrmeeting.accountdeletion.AccountDeletionUnitTest.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AccountDeletionProcessorTest {
    AccountDeletionProperties properties;
    AccountDeletionRepository repository;
    AccountDeletionWorkStore work;
    AccountDeletionWorkerLock locks;
    AccountCleanupService cleanup;
    AccountDeletionSafetyGateway gateway;
    AccountDeletionProcessor processor;
    AccountDeletionWorkStore.Claim claim;
    @BeforeEach void setup() throws Exception {
        properties = new AccountDeletionProperties();
        repository = mock(AccountDeletionRepository.class); work = mock(AccountDeletionWorkStore.class);
        locks = mock(AccountDeletionWorkerLock.class); cleanup = mock(AccountCleanupService.class);
        gateway = mock(AccountDeletionSafetyGateway.class);
        processor = new AccountDeletionProcessor(properties, repository, work, locks, cleanup, gateway, CLOCK);
        claim = new AccountDeletionWorkStore.Claim(1, 42, UUID.randomUUID(), null);
        when(locks.tryAcquire(1)).thenReturn(Optional.of(mock(AccountDeletionWorkerLock.Handle.class)));
        when(work.claim(1)).thenReturn(Optional.of(claim));
        when(repository.findDueIds(eq(NOW), eq(5), any())).thenReturn(List.of(1L));
    }
    void enable() { properties.setSchedulerEnabled(true); properties.setDestructiveEnabled(true); }
    @Test void disabledBatchDoesNotQueryDatabase() {
        processor.runBatch(); verifyNoInteractions(repository, work, cleanup, gateway);
    }
    @Test void transientStorageFailureIsRetriedWithoutUserDeletion() {
        enable(); doThrow(new StorageException(503, "synthetic")).when(cleanup).cleanup(claim);
        processor.runBatch();
        verify(work).fail(claim, "STORAGE_FAILURE", true); verify(work, never()).complete(any());
    }
    @Test void retentionOrOwnershipFailureRequiresManualReview() {
        enable(); doThrow(AccountDeletionException.conflict("STORAGE_OWNERSHIP_UNVERIFIED"))
            .when(cleanup).cleanup(claim);
        processor.runBatch();
        verify(work).fail(claim, "STORAGE_OWNERSHIP_UNVERIFIED", false);
        verify(work, never()).complete(any());
    }
    @Test void lockContentionSkipsProcessing() throws Exception {
        enable(); when(locks.tryAcquire(1)).thenReturn(Optional.empty());
        processor.runBatch(); verifyNoInteractions(work, cleanup);
    }
    @Test void completionFollowsExternalCleanup() {
        enable(); processor.runBatch();
        var order = inOrder(work, cleanup);
        order.verify(work).claim(1); order.verify(work).validateCleanup(claim);
        order.verify(cleanup).cleanup(claim); order.verify(work).complete(claim);
    }
    @Test void databasePreflightFailurePreventsAllExternalDeletion() {
        enable();
        doThrow(AccountDeletionException.conflict("MEETING_REVIEW_REQUIRED")).when(work).validateCleanup(claim);
        processor.runBatch();
        verifyNoInteractions(cleanup);
        verify(work).fail(claim, "MEETING_REVIEW_REQUIRED", false);
    }
    @Test void unrecognizedIntegrationErrorIsNotStoredAsPersonalData() {
        enable();
        doThrow(AccountDeletionException.conflict("private@example.invalid")).when(cleanup).cleanup(claim);
        processor.runBatch();
        verify(work).fail(claim, "CLEANUP_FAILURE", false);
    }
}
