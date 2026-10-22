package com.nukleus.vrmeeting.accountdeletion;

import com.google.cloud.storage.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import static com.nukleus.vrmeeting.accountdeletion.AccountDeletionSafetyGateway.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountCleanupServiceTest {
    AccountDeletionProperties properties;
    AccountDeletionSafetyGateway gateway;
    Storage storage;
    ObjectProvider<Storage> provider;
    AccountCleanupService cleanup;
    AccountDeletionWorkStore.Claim claim;
    OwnedObject object = new OwnedObject(42, "synthetic-bucket", "users/42/avatar.glb", 7);
    @SuppressWarnings("unchecked")
    @BeforeEach void setup() {
        properties = new AccountDeletionProperties();
        properties.setDestructiveEnabled(true); properties.setSchedulerEnabled(true);
        properties.setAllowedBuckets(Set.of("synthetic-bucket"));
        gateway = mock(AccountDeletionSafetyGateway.class); storage = mock(Storage.class);
        provider = mock(ObjectProvider.class); when(provider.getObject()).thenReturn(storage);
        claim = new AccountDeletionWorkStore.Claim(1, 42, UUID.randomUUID(),
            new UserSnapshot(42, "synthetic@example.invalid", List.of(), null, null, null));
        cleanup = new AccountCleanupService(properties, gateway, provider);
        when(gateway.prepareCleanup(claim.user())).thenReturn(new CleanupInventory(List.of(object)));
    }
    @Test void disabledDoesNotTouchStorageOrGateway() {
        properties.setDestructiveEnabled(false);
        assertThrows(AccountDeletionException.class, () -> cleanup.cleanup(claim));
        verifyNoInteractions(storage, gateway);
    }
    @Test void rejectsWrongUserSharedPrefixAndUnapprovedBucket() {
        for (OwnedObject invalid : List.of(new OwnedObject(99, object.bucket(), object.name(), 7),
                new OwnedObject(42, "unapproved", object.name(), 7),
                new OwnedObject(42, object.bucket(), "meetings/shared.glb", 7))) {
            when(gateway.prepareCleanup(claim.user())).thenReturn(new CleanupInventory(List.of(invalid)));
            assertThrows(AccountDeletionException.class, () -> cleanup.cleanup(claim));
        }
        verifyNoInteractions(storage);
    }
    @Test void requiresMetadataOwnershipNotJustStoredUrl() {
        BlobId id = BlobId.of(object.bucket(), object.name(), 7L);
        Blob blob = mock(Blob.class); when(storage.get(id)).thenReturn(blob);
        when(blob.getMetadata()).thenReturn(Map.of("nukleusUserId", "99", "nukleusExclusiveOwner", "true"));
        assertThrows(AccountDeletionException.class, () -> cleanup.cleanup(claim));
        verify(storage, never()).delete(any(BlobId.class), any(Storage.BlobSourceOption.class));
    }
    @Test void ownedGenerationDeletedAndMissingObjectIsIdempotent() {
        BlobId id = BlobId.of(object.bucket(), object.name(), 7L);
        Blob blob = mock(Blob.class);
        when(blob.getMetadata()).thenReturn(Map.of("nukleusUserId", "42", "nukleusExclusiveOwner", "true"));
        when(storage.get(id)).thenReturn(blob).thenReturn(null);
        cleanup.cleanup(claim); cleanup.cleanup(claim);
        verify(storage, times(1)).delete(eq(id), any(Storage.BlobSourceOption.class));
        verify(gateway, times(2)).cleanupExternalIdentities(claim.user());
    }
    @Test void storageFailureStopsBeforeExternalIdentityDeletion() {
        when(storage.get(any(BlobId.class))).thenThrow(new StorageException(503, "synthetic outage"));
        assertThrows(StorageException.class, () -> cleanup.cleanup(claim));
        verify(gateway, never()).cleanupExternalIdentities(any());
    }
    @Test void refusesCloudOperationsInsideDatabaseTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertThrows(IllegalStateException.class, () -> cleanup.cleanup(claim)); }
        finally { TransactionSynchronizationManager.clear(); }
        verifyNoInteractions(storage, gateway);
    }
}
