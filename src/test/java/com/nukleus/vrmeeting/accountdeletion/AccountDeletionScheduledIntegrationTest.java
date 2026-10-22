package com.nukleus.vrmeeting.accountdeletion;

import com.google.cloud.storage.*;
import com.nukleus.vrmeeting.accountdeletion.phase2.*;
import com.nukleus.vrmeeting.accountdeletion.phase3.*;
import com.nukleus.vrmeeting.security.JwtUtil;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Full HTTP -> verified machine token -> original worker -> isolated PostgreSQL. All cloud calls mocked. */
class AccountDeletionScheduledIntegrationTest {
    static KeyPair key;
    final Phase3IntegrationTest h = new Phase3IntegrationTest() {
        @Override void startContext(boolean enabled) {
            context = new AnnotationConfigWebApplicationContext();
            context.setServletContext(new MockServletContext());
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                "nukleus.account-deletion.integration.enabled=" + enabled,
                "nukleus.account-deletion.cleanup.automatic-preparation-enabled=true",
                "nukleus.account-deletion.cleanup.anonymize-meeting-references=true",
                "nukleus.account-deletion.trigger.enabled=true",
                "nukleus.account-deletion.trigger.audience=" + AccountDeletionScheduledTriggerTest.AUDIENCE,
                "nukleus.account-deletion.trigger.service-account-subject=" + AccountDeletionScheduledTriggerTest.SUBJECT,
                "nukleus.account-deletion.trigger.service-account-email=" + AccountDeletionScheduledTriggerTest.EMAIL);
            context.addBeanFactoryPostProcessor(factory -> {
                factory.registerSingleton("dataSource", db.dataSource);
                factory.registerSingleton("jdbcTemplate", db.jdbc);
                factory.registerSingleton("entityManager", db.entityManager);
                factory.registerSingleton("transactionManager", db.manager);
                factory.registerSingleton("userRepository", users);
                factory.registerSingleton("meetingRepository", meetings);
                factory.registerSingleton("accountDeletionRepository", db.repository);
                factory.registerSingleton("accountDeletionProperties", db.properties);
                factory.registerSingleton("accountDeletionClock", clock);
                factory.registerSingleton("objectMapper", json);
                factory.registerSingleton("storage", storage);
                factory.registerSingleton("jwtUtil", new JwtUtil());
                factory.registerSingleton("deletionProviderHttp", mock(ProviderHttp.class, call -> {
                    if (call.getMethod().getName().equals("exchange"))
                        throw new AssertionError("No live provider calls permitted");
                    return RETURNS_DEFAULTS.answer(call);
                }));
                try {
                    factory.registerSingleton("accountDeletionTriggerIdentity",
                        new AccountDeletionTriggerIdentity(AccountDeletionScheduledTriggerTest.properties(),
                            AccountDeletionScheduledTriggerTest.verifier(key, clock), clock));
                } catch (Exception e) { throw new AssertionError(e); }
            });
            context.register(WebConfiguration.class, AccountDeletionTriggerConfiguration.class,
                AccountDeletionScheduledTrigger.class, AccountDeletionScheduler.class);
            context.refresh();
            mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
        }
    };
    @BeforeAll static void keys() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); key = generator.generateKeyPair();
    }
    @BeforeEach void setup() throws Exception {
        h.database(); // Harness enforces loopback, synthetic database name and a fresh random schema.
        try (var stream = getClass().getResourceAsStream(
                "/db/account-deletion/V20261008_03__cleanup_evidence_and_google_reauthentication.sql");
             var connection = h.db.dataSource.getConnection(); var statement = connection.createStatement()) {
            assertNotNull(stream); statement.execute(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
        h.setup();
        assertFalse(h.bean(AccountDeletionScheduler.class).isRunning(), "HTTP mode must not also run an idle timer");
    }
    @AfterEach void close() { h.close(); h.cleanup(); }
    String token() { return AccountDeletionScheduledTriggerTest.signed(key, h.clock.instant()); }
    void tick() throws Exception {
        h.mvc.perform(post(AccountDeletionScheduledTrigger.PATH).servletPath(AccountDeletionScheduledTrigger.PATH)
                .header("Authorization", token())).andExpect(status().isNoContent());
    }
    void review(long id) {
        // Synthetic fixture ONLY: deliberately reviewed complete inventory. Never used for real accounts.
        try (var lease = h.bean(OperationFence.class).acquire(true)) {
            h.bean(CleanupInventoryStore.class).reviewCoverage(id, "b".repeat(64),
                EnumSet.allOf(ApprovedCleanupPlan.ReviewDomain.class));
        }
    }
    BlobId ownedPhoto(long id, boolean failOnce) {
        String name = "users/" + id + "/photo/" + UUID.randomUUID() + ".png";
        BlobId blobId = BlobId.of("synthetic-bucket", name, 25L);
        Blob blob = mock(Blob.class);
        when(blob.getBucket()).thenReturn(blobId.getBucket()); when(blob.getName()).thenReturn(name);
        when(blob.getGeneration()).thenReturn(25L);
        when(blob.getMetadata()).thenReturn(Map.of("nukleusUserId", Long.toString(id), "nukleusExclusiveOwner", "true"));
        AtomicBoolean present = new AtomicBoolean(true), failing = new AtomicBoolean(failOnce);
        when(h.storage.get(blobId)).thenAnswer(call -> present.get() ? blob : null);
        when(h.storage.delete(eq(blobId), any(Storage.BlobSourceOption.class))).thenAnswer(call -> {
            if (failing.getAndSet(false)) throw new StorageException(503, "synthetic unavailable");
            present.set(false); return true;
        });
        h.db.jdbc.update("insert into account_deletion_owned_objects(bucket,object_name,user_id,generation,kind) values(?,?,?,?,?)",
            blobId.getBucket(), name, id, 25L, "PHOTO");
        h.db.jdbc.update("update users set image_url=? where id=?", "https://storage.googleapis.com/synthetic-bucket/" + name, id);
        return blobId;
    }
    @Test void requestRevokesAndRestrictsThenScheduledTickDeletesOnlyAfter168Hours() throws Exception {
        long id = h.newUser(), other = h.newUser();
        BlobId object = ownedPhoto(id, false);
        String fullToken = h.login(id);
        h.request(fullToken); long request = h.db.requestFor(id).getId(); review(id);
        assertEquals(h.clock.instant().plus(Duration.ofHours(168)), h.db.requestFor(id).getScheduledDeletionAt());
        h.normal(fullToken, id, 401); h.normal(h.login(id), id, 403);
        h.clock.advance(Duration.ofHours(168).minusSeconds(1));
        tick(); assertTrue(h.users.findById(id).isPresent());
        verify(h.storage, never()).delete(any(BlobId.class), any(Storage.BlobSourceOption.class));
        h.clock.advance(Duration.ofSeconds(1));
        tick(); tick();
        assertTrue(h.users.findById(id).isEmpty()); assertTrue(h.users.findById(other).isPresent());
        verify(h.storage, times(1)).delete(eq(object), any(Storage.BlobSourceOption.class));
        assertEquals("COMPLETED", h.db.jdbc.queryForObject("select status from account_deletion_requests where id=?", String.class, request));
        assertNull(h.db.jdbc.queryForObject("select user_id from account_deletion_requests where id=?", Long.class, request));
        assertEquals(0, h.db.jdbc.queryForObject("select count(*) from account_deletion_sessions where user_id=?", Integer.class, id));
        assertEquals(0, h.db.jdbc.queryForObject("select count(*) from account_deletion_owned_objects where user_id=?", Integer.class, id));
    }
    @Test void cancellationBeforeDeadlineSurvivesLaterScheduledTicks() throws Exception {
        long id = h.newUser(); h.request(h.login(id)); review(id);
        h.clock.advance(Duration.ofHours(167)); h.cancel(h.login(id), 200);
        h.clock.advance(Duration.ofHours(2)); tick();
        assertTrue(h.users.findById(id).isPresent());
        assertEquals(AccountDeletionRequest.Status.CANCELLED, h.db.requestFor(id).getStatus());
        verifyNoInteractions(h.storage);
    }
    @Test void noTokenAdminOrEndUserCanReachPermanentWorker() throws Exception {
        long id = h.newUser(); String endUser = h.login(id);
        for (String bearer : List.of("", endUser, "Bearer " + new JwtUtil().generateToken(h.email(id)))) {
            h.mvc.perform(post(AccountDeletionScheduledTrigger.PATH).servletPath(AccountDeletionScheduledTrigger.PATH)
                    .header("Authorization", bearer)).andExpect(status().isUnauthorized());
        }
        // A service token cannot be used to become a user on existing APIs.
        h.mvc.perform(get("/api/avatar/by-email").servletPath("/api/avatar/by-email")
                .header("Authorization", token()).param("email", h.email(id))).andExpect(status().isUnauthorized());
        verifyNoInteractions(h.storage);
    }
    @Test void missingLegacyEvidenceRemainsBlockedBeforeStorageDeletion() throws Exception {
        long id = h.newUser();
        h.db.jdbc.update("update users set image_url=? where id=?",
            "https://storage.googleapis.com/synthetic-bucket/images/unproved.png", id);
        h.request(h.login(id));
        h.clock.advance(Duration.ofHours(168)); tick();
        assertTrue(h.users.findById(id).isPresent());
        assertEquals(AccountDeletionRequest.Status.FAILED, h.db.requestFor(id).getStatus());
        assertEquals("INTEGRATION_REQUIRED", h.db.requestFor(id).getLastError());
        verifyNoInteractions(h.storage);
    }
    @Test void transientStorageFailureIsRetriedOnFutureTickNotByDeletingAccountEarly() throws Exception {
        long id = h.newUser(); BlobId object = ownedPhoto(id, true);
        h.request(h.login(id)); review(id); h.clock.advance(Duration.ofHours(168)); tick();
        assertTrue(h.users.findById(id).isPresent());
        assertEquals(1, h.db.requestFor(id).getRetryCount()); assertEquals("STORAGE_FAILURE", h.db.requestFor(id).getLastError());
        tick(); verify(h.storage, times(1)).delete(eq(object), any(Storage.BlobSourceOption.class));
        h.clock.advance(Duration.ofMinutes(15)); tick();
        assertTrue(h.users.findById(id).isEmpty());
        verify(h.storage, times(2)).delete(eq(object), any(Storage.BlobSourceOption.class));
    }
    @Test void duplicateConcurrentDeliveryDeletesAnOwnedGenerationOnlyOnce() throws Exception {
        long id = h.newUser(); BlobId object = ownedPhoto(id, false);
        h.request(h.login(id)); review(id); h.clock.advance(Duration.ofHours(168));
        CountDownLatch go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Void> invoke = () -> { go.await(); tick(); return null; };
            var a = pool.submit(invoke); var b = pool.submit(invoke); go.countDown();
            a.get(20, TimeUnit.SECONDS); b.get(20, TimeUnit.SECONDS);
        }
        tick();
        assertTrue(h.users.findById(id).isEmpty());
        verify(h.storage, times(1)).delete(eq(object), any(Storage.BlobSourceOption.class));
    }
    @Test void readinessFailureDoesNotConsumeAttemptsAndReturnsRetryable503() throws Exception {
        long id = h.newUser(); h.request(h.login(id)); h.clock.advance(Duration.ofHours(168));
        h.db.jdbc.update("update account_deletion_integration_state set rollout_approved_at=null");
        h.mvc.perform(post(AccountDeletionScheduledTrigger.PATH).servletPath(AccountDeletionScheduledTrigger.PATH)
                .header("Authorization", token())).andExpect(status().isServiceUnavailable());
        assertEquals(AccountDeletionRequest.Status.PENDING, h.db.requestFor(id).getStatus());
        assertEquals(0, h.db.requestFor(id).getRetryCount()); verifyNoInteractions(h.storage);
    }
}
