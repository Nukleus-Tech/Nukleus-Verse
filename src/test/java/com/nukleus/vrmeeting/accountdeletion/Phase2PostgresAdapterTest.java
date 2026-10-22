package com.nukleus.vrmeeting.accountdeletion;

import com.google.cloud.storage.*;
import com.nukleus.vrmeeting.accountdeletion.AccountDeletionSafetyGateway.*;
import com.nukleus.vrmeeting.accountdeletion.phase2.*;
import com.nukleus.vrmeeting.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.nukleus.vrmeeting.accountdeletion.phase2.Phase2Ports.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Composes the existing package-private Phase 1 isolated-DB fixture without editing it.
 * Every test owns a NEW random synthetic schema; all cloud/provider calls remain mocks.
 */
class Phase2PostgresAdapterTest {
    AccountDeletionPostgresTest db;
    UserRepository users;
    PersonalDataInventoryAdapter inventory;
    static final String BUCKET = "phase2-synthetic-only";

    @BeforeEach void setup() throws Exception {
        db = new AccountDeletionPostgresTest();
        db.setupDatabase(); // Existing explicit local-test opt-in and URL allowlist are reused.
        users = new JpaRepositoryFactory(db.entityManager).getRepository(UserRepository.class);
        inventory = new PersonalDataInventoryAdapter(users, db.jdbc);
        db.properties.setAllowedBuckets(Set.of(BUCKET));
    }
    @AfterEach void close() { if (db != null) db.cleanupDatabase(); }
    UserSnapshot snapshot(long id) { return PersonalDataInventoryAdapter.snapshot(users.findById(id).orElseThrow()); }
    GcsReference reference(long id) { return new GcsReference(BUCKET, "users/" + id + "/avatar.glb"); }
    String url(long id) { return "https://storage.googleapis.com/" + BUCKET + "/users/" + id + "/avatar.glb"; }

    @Test void unsharedUserInventoryPassesWithoutChangingAnyUserField() {
        long id = db.newUser();
        var before = snapshot(id);
        inventory.requireCurrentAndUnshared(before, Set.of());
        assertEquals(before, snapshot(id));
        assertEquals("ACTIVE", db.jdbc.queryForObject("select account_status from users where id=?", String.class, id));
    }
    @Test void canonicalAliasInAnotherUserPreventsStorageCleanup() {
        long id = db.newUser(), other = db.newUser();
        db.jdbc.update("update users set image_url=? where id=?", url(id), id);
        db.jdbc.update("update users set avatar_url=? where id=?",
            "https://" + BUCKET + ".storage.googleapis.com/users%2F" + id + "%2Favatar.glb?signature=synthetic", other);
        assertEquals("STORAGE_OWNERSHIP_UNVERIFIED", assertThrows(AccountDeletionException.class, () ->
            inventory.requireCurrentAndUnshared(snapshot(id), Set.of(reference(id)))).getCode());
    }
    @Test void sharedMeetingAssetBlocksEvenWhenOtherUsersOwnTheMeeting() {
        long id = db.newUser();
        db.jdbc.update("""
            insert into meetings(meeting_id, room_code, host_email, status, recording_url)
            values (?, '123456', 'unrelated@example.invalid', 'ENDED', ?)
            """, UUID.randomUUID().toString(), "gs://" + BUCKET + "/users/" + id + "/avatar.glb");
        assertEquals("STORAGE_OWNERSHIP_UNVERIFIED", assertThrows(AccountDeletionException.class, () ->
            inventory.requireCurrentAndUnshared(snapshot(id), Set.of(reference(id)))).getCode());
        assertEquals(1, db.jdbc.queryForObject("select count(*) from meetings", Integer.class));
    }
    @Test void meetingMembershipUsesExactCaseInsensitiveCsvTokensNotSubstrings() {
        long id = db.newUser(); String email = snapshot(id).email();
        String meetingId = UUID.randomUUID().toString();
        db.jdbc.update("""
            insert into meetings(meeting_id, room_code, host_email, status, participant_emails)
            values (?, '123456', 'unrelated@example.invalid', 'ENDED', ?)
            """, meetingId, email + ".not-the-same-user");
        inventory.requireCurrentAndUnshared(snapshot(id), Set.of());
        db.jdbc.update("update meetings set participant_emails=? where meeting_id=?",
            "someone@example.invalid, " + email.toUpperCase(Locale.ROOT) + " ", meetingId);
        assertEquals("MEETING_REVIEW_REQUIRED", assertThrows(AccountDeletionException.class, () ->
            inventory.requireCurrentAndUnshared(snapshot(id), Set.of())).getCode());
    }
    @Test void unknownForeignKeyRequiresReviewBeforeExternalDeletionEvenWhenEmpty() {
        long id = db.newUser();
        db.jdbc.execute("create table synthetic_retention_guard(user_id bigint references users(id))");
        assertThrows(AccountDeletionException.class, () -> inventory.requireCurrentAndUnshared(snapshot(id), Set.of()));
        assertEquals(1, db.jdbc.queryForObject("select count(*) from users where id=?", Integer.class, id));
    }
    @Test void ambiguousCaseVariantEmailPreventsDeletingAnotherUsersOtps() {
        long id = db.newUser(), other = db.newUser();
        String email = snapshot(id).email();
        db.jdbc.update("update users set email=? where id=?", email.toUpperCase(Locale.ROOT), other);
        db.jdbc.update("insert into email_otp(email, otp) values (?, 'synthetic')", email);
        assertThrows(AccountDeletionException.class, () -> inventory.requireCurrentAndUnshared(snapshot(id), Set.of()));
        assertEquals(1, db.jdbc.queryForObject("select count(*) from email_otp", Integer.class));
    }
    @Test void newGatewayResumesPartialStorageFailureAndCompletesExistingWorkerSafely() throws Exception {
        Harness h = new Harness();
        AtomicBoolean failOnce = new AtomicBoolean(true);
        when(h.storage.delete(any(BlobId.class), any(Storage.BlobSourceOption.class))).thenAnswer(call -> {
            BlobId id = call.getArgument(0);
            if (id.getName().endsWith("avatar.glb") && failOnce.getAndSet(false)) {
                throw new StorageException(503, "synthetic outage");
            }
            return h.blobs.remove(id) != null;
        });
        h.processor(db.dueClock()).runBatch();
        var failed = db.repository.findById(h.requestId).orElseThrow();
        assertEquals(AccountDeletionRequest.Status.FAILED, failed.getStatus());
        assertEquals(1, failed.getRetryCount()); assertTrue(users.existsById(h.userId));
        assertEquals(1, h.blobs.size()); // Photo was deleted; avatar must be retried.
        assertEquals(2, h.journal.verified.size()); // Durable evidence existed before either deletion.
        h.processor(Clock.fixed(db.dueClock().instant().plusSeconds(900), ZoneOffset.UTC)).runBatch();
        var completed = db.repository.findById(h.requestId).orElseThrow();
        assertEquals(AccountDeletionRequest.Status.COMPLETED, completed.getStatus());
        assertEquals(2, completed.getRetryCount()); assertNull(completed.getUserId());
        assertFalse(users.existsById(h.userId)); assertTrue(h.blobs.isEmpty());
        assertEquals(0, db.jdbc.queryForObject("select count(*) from email_otp where email=?", Integer.class, h.email));
        assertEquals(1, db.jdbc.queryForObject(
            "select count(*) from users where email='migration-canary@example.invalid'", Integer.class));
    }
    @Test void twoWorkerInstancesCannotProcessTheSamePhase2CleanupConcurrently() throws Exception {
        Harness h = new Harness();
        CountDownLatch enteredCloudRead = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        when(h.storage.get(any(BlobId.class))).thenAnswer(call -> {
            if (first.getAndSet(false)) {
                enteredCloudRead.countDown();
                if (!release.await(15, TimeUnit.SECONDS)) throw new AssertionError("Synthetic worker wait expired");
            }
            return h.blobs.get(call.getArgument(0));
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var running = executor.submit(() -> h.processor(db.dueClock()).runBatch());
            try {
                assertTrue(enteredCloudRead.await(10, TimeUnit.SECONDS));
                h.processor(db.dueClock()).runBatch(); // Skips the other instance's advisory lock.
                assertEquals(1, db.repository.findById(h.requestId).orElseThrow().getRetryCount());
            } finally { release.countDown(); }
            running.get(20, TimeUnit.SECONDS);
        }
        assertEquals(AccountDeletionRequest.Status.COMPLETED,
            db.repository.findById(h.requestId).orElseThrow().getStatus());
        verify(h.storage, times(2)).delete(any(BlobId.class), any(Storage.BlobSourceOption.class));
        assertFalse(users.existsById(h.userId));
    }

    @Test void providerOutageRetriesWithSameManifestAfterStorageAlreadyDeleted() {
        Harness h = new Harness(true);
        doThrow(new RetryableIntegrationFailure()).doNothing().when(h.google)
            .removeApplicationReference(eq(h.userId), eq("synthetic-google-subject"), any(UUID.class));
        h.processor(db.dueClock()).runBatch();
        var failed = db.repository.findById(h.requestId).orElseThrow();
        assertEquals(AccountDeletionRequest.Status.FAILED, failed.getStatus());
        assertNotNull(failed.getNextAttemptAt()); assertTrue(h.blobs.isEmpty());
        assertTrue(users.existsById(h.userId));
        h.processor(Clock.fixed(db.dueClock().instant().plusSeconds(900), ZoneOffset.UTC)).runBatch();
        assertEquals(AccountDeletionRequest.Status.COMPLETED,
            db.repository.findById(h.requestId).orElseThrow().getStatus());
        verify(h.google, times(2)).removeApplicationReference(
            h.userId, "synthetic-google-subject", h.journal.plan.planId());
        assertTrue(h.journal.external.contains(ExternalProvider.GOOGLE_APPLICATION_GRANT));
        assertFalse(users.existsById(h.userId));
    }

    final class Harness {
        final long userId, requestId;
        final String email;
        final Map<BlobId, Blob> blobs = new ConcurrentHashMap<>();
        final Storage storage = mock(Storage.class);
        final ExternalReferenceClient google = mock(ExternalReferenceClient.class);
        final ObjectProvider<Storage> provider;
        final TestJournal journal;
        final Phase2SafetyGateway gateway;
        Harness() { this(false); }
        @SuppressWarnings("unchecked")
        Harness(boolean withGoogleReference) {
            userId = db.newUser();
            if (withGoogleReference) {
                db.jdbc.update("update users set google_id=? where id=?", "synthetic-google-subject", userId);
            }
            String photo = "https://storage.googleapis.com/" + BUCKET + "/users/" + userId + "/photo.png";
            db.jdbc.update("update users set image_url=?, avatar_url=? where id=?", photo, url(userId), userId);
            email = snapshot(userId).email();
            db.jdbc.update("insert into email_otp(email, otp) values (?, 'synthetic')", email);
            db.service.request(userId);
            requestId = db.requestFor(userId).getId();
            List<OwnedObject> objects = List.of(new OwnedObject(userId, BUCKET, "users/" + userId + "/photo.png", 7),
                new OwnedObject(userId, BUCKET, "users/" + userId + "/avatar.glb", 8));
            var plan = new ApprovedCleanupPlan(UUID.randomUUID(), requestId, userId,
                CleanupFingerprint.of(snapshot(userId)), objects,
                EnumSet.allOf(ApprovedCleanupPlan.ReviewDomain.class));
            journal = new TestJournal(plan);
            for (OwnedObject object : objects) {
                Blob blob = mock(Blob.class);
                when(blob.getBucket()).thenReturn(object.bucket()); when(blob.getName()).thenReturn(object.name());
                when(blob.getGeneration()).thenReturn(object.generation());
                when(blob.getMetadata()).thenReturn(Map.of("nukleusUserId", Long.toString(userId),
                    "nukleusExclusiveOwner", "true"));
                blobs.put(BlobId.of(object.bucket(), object.name(), object.generation()), blob);
            }
            when(storage.get(any(BlobId.class))).thenAnswer(call -> blobs.get(call.getArgument(0)));
            when(storage.delete(any(BlobId.class), any(Storage.BlobSourceOption.class)))
                .thenAnswer(call -> blobs.remove(call.getArgument(0)) != null);
            provider = mock(ObjectProvider.class); when(provider.getObject()).thenReturn(storage);
            var policy = new Phase2Policy(true, true);
            var sessions = mock(SessionAuthority.class);
            var revocation = new SessionRevocationAdapter(policy, sessions, db.repository, db.dueClock());
            var ownership = new StorageOwnershipAdapter(policy, db.properties, provider, journal);
            var external = new ExternalReferenceCleanupAdapter(policy, db.properties, journal,
                withGoogleReference ? Map.of(ExternalProvider.GOOGLE_APPLICATION_GRANT, google) : Map.of());
            // Synthetic test doubles ONLY. Neither is a production readiness/revocation implementation.
            gateway = new Phase2SafetyGateway(policy, db.properties, mock(ProtectionReadiness.class),
                revocation, journal, inventory, ownership, external);
        }
        AccountDeletionProcessor processor(Clock clock) {
            return new AccountDeletionProcessor(db.properties, db.repository, db.workAt(clock),
                new AccountDeletionWorkerLock(db.dataSource),
                new AccountCleanupService(db.properties, gateway, provider), gateway, clock);
        }
    }
    /** Test-only memory store simulates durable responses; explicitly NOT a production journal. */
    static final class TestJournal implements CleanupJournal {
        final ApprovedCleanupPlan plan;
        final Set<OwnedObject> verified = ConcurrentHashMap.newKeySet();
        final Set<ExternalProvider> external = ConcurrentHashMap.newKeySet();
        TestJournal(ApprovedCleanupPlan plan) { this.plan = plan; }
        public ApprovedCleanupPlan approvedPlan(long requestId, long userId) {
            if (plan.requestId() != requestId || plan.userId() != userId) throw AccountDeletionException.integration();
            return plan;
        }
        public boolean generationWasVerified(UUID id, OwnedObject object) {
            return plan.planId().equals(id) && verified.contains(object);
        }
        public void recordVerifiedGenerations(UUID id, Set<OwnedObject> objects) {
            if (!plan.planId().equals(id) || !plan.objects().containsAll(objects)) throw AccountDeletionException.integration();
            verified.addAll(objects);
        }
        public boolean externalCleanupCompleted(UUID id, ExternalProvider provider) {
            return plan.planId().equals(id) && external.contains(provider);
        }
        public void recordExternalCleanupCompleted(UUID id, ExternalProvider provider) {
            if (!plan.planId().equals(id)) throw AccountDeletionException.integration();
            external.add(provider);
        }
    }
}
