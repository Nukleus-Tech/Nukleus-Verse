package com.nukleus.vrmeeting.accountdeletion.phase2;

import com.google.cloud.storage.*;
import com.nukleus.vrmeeting.accountdeletion.*;
import com.nukleus.vrmeeting.accountdeletion.AccountDeletionSafetyGateway.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import java.util.*;
import static com.nukleus.vrmeeting.accountdeletion.phase2.Phase2TestSupport.*;
import static com.nukleus.vrmeeting.accountdeletion.phase2.Phase2Ports.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StorageOwnershipAdapterTest {
    Storage storage; ObjectProvider<Storage> provider; CleanupJournal journal;
    StorageOwnershipAdapter adapter; AccountDeletionProperties properties;
    @SuppressWarnings("unchecked") @BeforeEach void setup() {
        storage = mock(Storage.class); provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(storage); journal = mock(CleanupJournal.class);
        properties = properties(); adapter = new StorageOwnershipAdapter(ENABLED, properties, provider, journal);
    }
    Blob owned(OwnedObject object) {
        Blob blob = mock(Blob.class);
        when(blob.getBucket()).thenReturn(object.bucket()); when(blob.getName()).thenReturn(object.name());
        when(blob.getGeneration()).thenReturn(object.generation());
        when(blob.getMetadata()).thenReturn(Map.of("nukleusUserId", Long.toString(object.userId()),
            "nukleusExclusiveOwner", "true"));
        return blob;
    }
    @Test void canonicalComparisonHandlesSignedAndGsAliasesWithoutNetwork() {
        var expected = new GcsReference(BUCKET, "users/42/photo.png");
        for (String url : List.of(URL, URL + "?X-Goog-Signature=synthetic",
                "gs://" + BUCKET + "/users/42/photo.png",
                "https://" + BUCKET + ".storage.googleapis.com/users%2F42%2Fphoto.png")) {
            assertEquals(expected, GcsReference.parse(url).orElseThrow());
        }
        assertEquals("users/42/a+b.png", GcsReference.parse(
            "https://storage.googleapis.com/" + BUCKET + "/users/42/a+b.png").orElseThrow().objectName());
        verifyNoInteractions(storage);
    }
    @Test void unrecognizedOrAmbiguousUrlsAreNotOwnershipEvidence() {
        for (String url : List.of("https://example.invalid/object", "file:///private/photo",
                "http://storage.googleapis.com/" + BUCKET + "/users/42/photo.png", URL + "#fragment",
                "https://name@storage.googleapis.com/" + BUCKET + "/users/42/photo.png",
                "https://storage.googleapis.com:443/" + BUCKET + "/users/42/photo.png")) {
            assertTrue(GcsReference.parse(url).isEmpty());
        }
    }
    @Test void everyCurrentPhotoAndAvatarReferenceMustBeInManifest() {
        var user = new UserSnapshot(42, "synthetic42@example.invalid", List.of(URL, URL + ".glb"),
            null, null, null);
        var plan = plan(user, List.of(object()));
        assertThrows(AccountDeletionException.class, () -> adapter.validateReferences(plan, user));
        verifyNoInteractions(storage, journal);
    }
    @Test void partialOrMismatchedReviewCannotApproveCleanup() {
        var good = plan(snapshot(), List.of(object()));
        var partial = new ApprovedCleanupPlan(good.planId(), 11, 42, good.snapshotFingerprint(),
            good.objects(), Set.of(ApprovedCleanupPlan.ReviewDomain.STORAGE_AND_HISTORICAL_OBJECTS));
        assertThrows(AccountDeletionException.class, () -> adapter.validateReferences(partial, snapshot()));
        assertThrows(AccountDeletionException.class, () -> good.requireMatches(99, snapshot()));
        var changed = new UserSnapshot(42, "changed@example.invalid", List.of(URL), null, null, null);
        assertThrows(AccountDeletionException.class, () -> good.requireMatches(11, changed));
    }
    @Test void legacySharedPrefixWrongBucketWrongOwnerAndDuplicateGenerationsRejected() {
        for (OwnedObject asset : List.of(new OwnedObject(42, BUCKET, "images/legacy.png", 7),
                new OwnedObject(42, "wrong-bucket", "users/42/photo.png", 7),
                new OwnedObject(99, BUCKET, "users/42/photo.png", 7))) {
            assertThrows(AccountDeletionException.class,
                () -> adapter.validateReferences(plan(snapshot(), List.of(asset)), snapshot()));
        }
        assertThrows(AccountDeletionException.class, () ->
            adapter.validateReferences(plan(snapshot(), List.of(object(), object())), snapshot()));
    }
    @Test void wrongMetadataOrGenerationFailsBeforeAnyJournalApproval() {
        var plan = plan(snapshot(), List.of(object()));
        Blob bad = owned(object()); when(bad.getMetadata()).thenReturn(Map.of("nukleusUserId", "99"));
        when(storage.get(any(BlobId.class))).thenReturn(bad);
        assertThrows(AccountDeletionException.class, () -> adapter.verifyAndJournalGenerations(plan));
        verify(journal, never()).recordVerifiedGenerations(any(), any());
    }
    @Test void missingObjectWithoutDurablePriorEvidenceIsNotSilentlyAccepted() {
        var plan = plan(snapshot(), List.of(object()));
        assertThrows(AccountDeletionException.class, () -> adapter.verifyAndJournalGenerations(plan));
        verify(journal, never()).recordVerifiedGenerations(any(), any());
    }
    @Test void allObjectsVerifiedBeforeAtomicEvidenceWrite() {
        var second = new OwnedObject(42, BUCKET, "users/42/avatar.glb", 8);
        var plan = plan(snapshot(), List.of(object(), second));
        Blob firstBlob = owned(object()), secondBlob = owned(second);
        when(storage.get(BlobId.of(BUCKET, object().name(), 7L))).thenReturn(firstBlob);
        when(storage.get(BlobId.of(BUCKET, second.name(), 8L))).thenReturn(secondBlob);
        adapter.verifyAndJournalGenerations(plan);
        var order = inOrder(storage, journal);
        order.verify(storage).get(BlobId.of(BUCKET, object().name(), 7L));
        order.verify(storage).get(BlobId.of(BUCKET, second.name(), 8L));
        order.verify(journal).recordVerifiedGenerations(plan.planId(), Set.of(object(), second));
        verify(storage, never()).delete(any(BlobId.class), any(Storage.BlobSourceOption.class));
    }
    @Test void retryAfterPartialDeletionUsesPinnedDurableGenerationEvidence() {
        var plan = plan(snapshot(), List.of(object()));
        when(journal.generationWasVerified(plan.planId(), object())).thenReturn(true);
        assertDoesNotThrow(() -> adapter.verifyAndJournalGenerations(plan));
        assertDoesNotThrow(() -> adapter.requireAllApprovedGenerationsGone(plan));
    }
    @Test void remainingObjectBlocksIdentityAndDatabaseCompletion() {
        var plan = plan(snapshot(), List.of(object()));
        when(journal.generationWasVerified(plan.planId(), object())).thenReturn(true);
        Blob blob = owned(object());
        when(storage.get(any(BlobId.class))).thenReturn(blob);
        assertEquals("STORAGE_CLEANUP_INCOMPLETE", assertThrows(AccountDeletionException.class,
            () -> adapter.requireAllApprovedGenerationsGone(plan)).getCode());
    }
    @Test void disabledWorkerDoesNotAcquireCloudClient() {
        properties.setDestructiveEnabled(false);
        assertThrows(AccountDeletionException.class,
            () -> adapter.verifyAndJournalGenerations(plan(snapshot(), List.of(object()))));
        verifyNoInteractions(storage, journal);
        verify(provider, never()).getObject();
    }
    @Test void evidenceObjectsDoNotExposePersonalLocatorsViaToString() {
        assertEquals("ApprovedCleanupPlan[redacted]", plan(snapshot(), List.of(object())).toString());
        assertEquals("GcsReference[redacted]", new GcsReference(BUCKET, "users/42/photo.png").toString());
    }
}
