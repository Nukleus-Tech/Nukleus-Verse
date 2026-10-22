package com.nukleus.vrmeeting.accountdeletion;

import com.fasterxml.jackson.databind.*;
import com.google.cloud.storage.*;
import com.nukleus.vrmeeting.accountdeletion.phase2.*;
import com.nukleus.vrmeeting.accountdeletion.phase3.*;
import com.nukleus.vrmeeting.controller.*;
import com.nukleus.vrmeeting.model.User;
import com.nukleus.vrmeeting.repository.*;
import com.nukleus.vrmeeting.security.*;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.mock.web.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real PG + real SecurityFilterChain/Phase 1/2/3 beans. No Boot application, sockets, GCP or .env. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Phase3IntegrationTest {
    final AccountDeletionPostgresTest db = new AccountDeletionPostgresTest();
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    AnnotationConfigWebApplicationContext context;
    MutableClock clock;
    MockMvc mvc;
    Storage storage;
    UserRepository users;
    MeetingRepository meetings;
    @Configuration(proxyBeanMethods=false) @EnableWebSecurity @EnableWebMvc
    @Import({SecurityConfig.class, JwtFilter.class, Phase3Configuration.class,
        AuthController.class, AvatarController.class, MeetingController.class, StorageController.class,
        AdminUserController.class, AccountDeletionController.class, AccountDeletionExceptionHandler.class,
        IntegrationExceptionHandler.class, AuthenticatedAccountResolver.class, AccountDeletionService.class,
        AccountDeletionWorkStore.class, AccountDeletionWorkerLock.class, AccountCleanupService.class,
        AccountDeletionProcessor.class})
    static class WebConfiguration {}

    @BeforeAll void database() throws Exception {
        db.setupDatabase();
        try (var stream = getClass().getResourceAsStream(
                "/db/account-deletion/V20261008_02__account_deletion_integration.sql");
             var connection = db.dataSource.getConnection(); var statement = connection.createStatement()) {
            assertNotNull(stream);
            statement.execute(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
        users = transactionalRepository(UserRepository.class);
        meetings = transactionalRepository(MeetingRepository.class);
    }
    <T, R extends org.springframework.data.repository.Repository<T, Long>> R transactionalRepository(Class<R> type) {
        // Reproduce repository transaction advice without bootstrapping the production application.
        var proxy = new org.springframework.aop.framework.ProxyFactory(
            new JpaRepositoryFactory(db.entityManager).getRepository(type));
        var advice = new org.springframework.transaction.interceptor.TransactionInterceptor();
        advice.setTransactionManager(db.manager);
        advice.setTransactionAttributeSource(new org.springframework.transaction.interceptor.MatchAlwaysTransactionAttributeSource());
        proxy.addAdvice(advice);
        return type.cast(proxy.getProxy());
    }
    @BeforeEach void setup() {
        db.jdbc.update("update account_deletion_integration_state set enforcement_required=false,rollout_approved_at=null");
        clock = new MutableClock(AccountDeletionUnitTest.NOW);
        storage = mock(Storage.class);
        db.properties.setApiEnabled(true); db.properties.setSchedulerEnabled(true);
        db.properties.setDestructiveEnabled(true); db.properties.setAllowedBuckets(Set.of("synthetic-bucket", "nukleus-verse-storage"));
        startContext(true);
        approveRollout();
    }
    void startContext(boolean enabled) {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
            "nukleus.account-deletion.integration.enabled=" + enabled);
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
        });
        context.register(WebConfiguration.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }
    void approveRollout() {
        db.jdbc.update("update account_deletion_integration_state set enforcement_required=true,rollout_approved_at=now()");
    }
    @AfterEach void close() {
        SecurityContextHolder.clearContext();
        if (context != null) context.close();
    }
    @AfterAll void cleanup() { db.cleanupDatabase(); }
    <T> T bean(Class<T> type) { return context.getBean(type); }
    long newUser() {
        long id = db.newUser();
        db.jdbc.update("update users set password='synthetic-password' where id=?", id);
        return id;
    }
    String email(long id) { return users.findById(id).orElseThrow().getEmail(); }
    String login(long id) throws Exception {
        var result = mvc.perform(post("/api/auth/login").servletPath("/api/auth/login")
            .contentType("application/json").content(json.writeValueAsString(
                Map.of("email", email(id), "password", "synthetic-password"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andReturn();
        return "Bearer " + json.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }
    void request(String token) throws Exception {
        mvc.perform(post("/api/account/deletion-request").servletPath("/api/account/deletion-request")
            .header("Authorization", token).contentType("application/json").content("{\"confirm\":true}"))
            .andExpect(status().isCreated());
    }
    void status(String token, int expected) throws Exception {
        mvc.perform(get("/api/account/deletion-status").servletPath("/api/account/deletion-status")
            .header("Authorization", token)).andExpect(status().is(expected));
    }
    static org.springframework.test.web.servlet.result.StatusResultMatchers status() {
        return org.springframework.test.web.servlet.result.MockMvcResultMatchers.status();
    }
    void cancel(String token, int expected) throws Exception {
        mvc.perform(post("/api/account/cancel-deletion").servletPath("/api/account/cancel-deletion")
            .header("Authorization", token)).andExpect(status().is(expected));
    }
    void normal(String token, long userId, int expected) throws Exception {
        mvc.perform(get("/api/avatar/by-email").servletPath("/api/avatar/by-email")
            .header("Authorization", token).param("email", email(userId))).andExpect(status().is(expected));
    }
    ApprovedCleanupPlan plan(long userId, List<AccountDeletionSafetyGateway.OwnedObject> objects) {
        var snapshot = PersonalDataInventoryAdapter.snapshot(users.findById(userId).orElseThrow());
        return new ApprovedCleanupPlan(UUID.randomUUID(), db.requestFor(userId).getId(), userId,
            CleanupFingerprint.of(snapshot), objects, EnumSet.allOf(ApprovedCleanupPlan.ReviewDomain.class));
    }
    void install(ApprovedCleanupPlan plan) {
        try (var lease = bean(OperationFence.class).acquire(true)) {
            bean(JdbcCleanupJournal.class).installReviewedPlan(plan);
        }
    }
    void process(long id) { bean(AccountDeletionProcessor.class).process(db.requestFor(id).getId()); }

    @Test void realPasswordLoginIssuesOnlyHashedDurableEndUserTokens() throws Exception {
        long id = newUser();
        String token = login(id);
        assertTrue(token.matches("Bearer nv1_[A-Za-z0-9_-]{43}"));
        assertEquals(JdbcSessionAuthority.digest(token.substring(7)), db.jdbc.queryForObject(
            "select token_hash from account_deletion_sessions where user_id=?", String.class, id));
        status(token, 200);
        mvc.perform(post("/api/auth/login").servletPath("/api/auth/login").contentType("application/json")
            .content(json.writeValueAsString(Map.of("email", email(id), "password", "wrong"))))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }
    @Test void adminJwtAndForgedPrincipalCannotBecomeUserIdentity() throws Exception {
        String admin = "Bearer " + new JwtUtil().generateToken("admin@example.invalid");
        status(admin, 401);
        long id = newUser();
        mvc.perform(post("/api/account/deletion-request").servletPath("/api/account/deletion-request")
            .principal(AccountDeletionUnitTest.userAuthentication(id, clock.instant()))
            .contentType("application/json").content("{\"confirm\":true}")).andExpect(status().isUnauthorized());
        assertTrue(db.repository.findFirstByUserIdOrderByRequestedAtDescIdDesc(id).isEmpty());
        status("Bearer nv1_" + "A".repeat(43), 401);
    }
    @Test void sevenDayDeadlineIdentityAndRevocationSurviveNewSessionAuthority() throws Exception {
        long id = newUser(), other = newUser();
        String token = login(id), otherToken = login(other);
        mvc.perform(post("/api/account/deletion-request?userId=" + other).servletPath("/api/account/deletion-request")
            .header("Authorization", token).contentType("application/json").content("{\"confirm\":true}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.userId").doesNotExist());
        assertEquals(clock.instant().plus(Duration.ofHours(168)), db.requestFor(id).getScheduledDeletionAt());
        assertTrue(db.repository.findFirstByUserIdOrderByRequestedAtDescIdDesc(other).isEmpty());
        status(token, 401); normal(otherToken, other, 200);
        var newAuthority = new JdbcSessionAuthority(db.jdbc, users, db.repository, bean(Phase3Properties.class),
            bean(OperationFence.class), clock);
        assertThrows(AccountDeletionException.class, () -> newAuthority.authenticate(token));
        assertEquals("ACTIVE", users.findById(id).orElseThrow().getAccountStatus());
    }
    @Test void cancellationRequiresFreshDeletionOnlySessionAndCannotResurrectOldToken() throws Exception {
        long id = newUser();
        String old = login(id); request(old);
        String restricted = login(id);
        status(restricted, 200); normal(restricted, id, 403);
        cancel(restricted, 200);
        status(old, 401); normal(restricted, id, 403);
        normal(login(id), id, 200);
        assertEquals(AccountDeletionRequest.Status.CANCELLED, db.requestFor(id).getStatus());
    }
    @Test void pendingCommitAloneRevokesTokensEvenIfFollowupUpdateWasLost() throws Exception {
        long id = newUser();
        String token = login(id);
        // Simulate process loss immediately after Phase 1 request transaction committed.
        db.transactions.executeWithoutResult(tx -> db.repository.saveAndFlush(
            AccountDeletionRequest.pending(id, clock.instant(), clock.instant().plus(Duration.ofDays(7)))));
        assertNull(db.jdbc.queryForObject("select revoked_at from account_deletion_sessions where user_id=?",
            java.sql.Timestamp.class, id));
        status(token, 401);
        cancel(login(id), 200);
        status(token, 401);
    }
    @Test void recentAuthAndCancellationDeadlineAreEnforced() throws Exception {
        long id = newUser(); String token = login(id);
        clock.advance(Duration.ofMinutes(11));
        mvc.perform(post("/api/account/deletion-request").servletPath("/api/account/deletion-request")
            .header("Authorization", token).contentType("application/json").content("{\"confirm\":true}"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("RECENT_AUTHENTICATION_REQUIRED"));
        request(login(id));
        clock.advance(Duration.ofDays(7)); cancel(login(id), 409);
    }
    @Test void adminBlockUnblockAndRoleIsolationArePreserved() throws Exception {
        long id = newUser(); String userToken = login(id);
        String admin = "Bearer " + new JwtUtil().generateToken("admin@example.invalid");
        mvc.perform(put("/api/admin/users/" + id + "/block").servletPath("/api/admin/users/" + id + "/block")
            .header("Authorization", userToken)).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/admin/users/" + id + "/block").servletPath("/api/admin/users/" + id + "/block")
            .header("Authorization", admin)).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        normal(userToken, id, 403);
        mvc.perform(put("/api/admin/users/" + id + "/unblock").servletPath("/api/admin/users/" + id + "/unblock")
            .header("Authorization", admin)).andExpect(status().isOk());
        normal(userToken, id, 200);
        assertEquals("ACTIVE", users.findById(id).orElseThrow().getAccountStatus());
    }
    @Test void otherAccountAndSharedMeetingWritesAreDenied() throws Exception {
        long id = newUser(), other = newUser(); String token = login(id);
        mvc.perform(post("/api/avatar/save").servletPath("/api/avatar/save").header("Authorization", token)
            .contentType("application/json").content(json.writeValueAsString(Map.of("email", email(other)))))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/meeting/create").servletPath("/api/meeting/create").header("Authorization", token)
            .contentType("application/json").content(json.writeValueAsString(Map.of("hostEmail", email(other), "roomCode", "1234"))))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/meeting/join").servletPath("/api/meeting/join").header("Authorization", token)
            .contentType("application/json").content(json.writeValueAsString(Map.of("userEmail", email(other), "roomCode", "1234"))))
            .andExpect(status().isForbidden());
        verifyNoInteractions(storage);
    }
    @Test void activeMeetingCreateJoinLeaveAndAvatarSaveStillWorkWithVerifiedUsers() throws Exception {
        long id = newUser(); String token = login(id);
        mvc.perform(post("/api/meeting/create").servletPath("/api/meeting/create").header("Authorization", token)
            .contentType("application/json").content(json.writeValueAsString(Map.of("hostEmail", email(id), "roomCode", "872634"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        mvc.perform(post("/api/meeting/join").servletPath("/api/meeting/join").header("Authorization", token)
            .contentType("application/json").content(json.writeValueAsString(Map.of("userEmail", email(id), "roomCode", "872634"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        mvc.perform(post("/api/meeting/leave").servletPath("/api/meeting/leave").header("Authorization", token)
            .contentType("application/json").content(json.writeValueAsString(Map.of("userEmail", email(id)))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        mvc.perform(post("/api/avatar/save").servletPath("/api/avatar/save").header("Authorization", token)
            .contentType("application/json").content(json.writeValueAsString(Map.of("email", email(id), "avatarStatus", "READY"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
    }
    @Test void newUploadsUseVerifiedOwnerMetadataAndPersistExactGeneration() throws Exception {
        long id = newUser(); String token = login(id);
        when(storage.create(any(BlobInfo.class), any(byte[].class), any(Storage.BlobTargetOption[].class)))
            .thenAnswer(call -> {
                BlobInfo info = call.getArgument(0);
                assertEquals(Long.toString(id), info.getMetadata().get("nukleusUserId"));
                assertEquals("true", info.getMetadata().get("nukleusExclusiveOwner"));
                assertTrue(info.getName().startsWith("users/" + id + "/photo/"));
                Blob blob = mock(Blob.class);
                when(blob.getBucket()).thenReturn(info.getBucket()); when(blob.getName()).thenReturn(info.getName());
                when(blob.getGeneration()).thenReturn(17L); return blob;
            });
        mvc.perform(multipart("/api/storage/upload-image")
            .file(new MockMultipartFile("file", "../../name.png", "image/png", new byte[]{1,2,3}))
            .servletPath("/api/storage/upload-image").header("Authorization", token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        assertEquals(17L, db.jdbc.queryForObject(
            "select generation from account_deletion_owned_objects where user_id=?", Long.class, id));
    }
    @Test void unverifiedUploadsAndRemoteArchivesDoNotCallStorage() throws Exception {
        mvc.perform(multipart("/api/storage/upload-image")
            .file(new MockMultipartFile("file", "x.png", "image/png", new byte[]{1})).servletPath("/api/storage/upload-image"))
            .andExpect(status().isUnauthorized());
        long id = newUser(); String token = login(id);
        mvc.perform(post("/api/storage/archive-remote-file").servletPath("/api/storage/archive-remote-file")
            .header("Authorization", token).contentType("application/json").content("{\"remoteUrl\":\"http://127.0.0.1/secret\"}"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(storage);
    }
    @Test void globalFenceExcludesCrossInstanceWritersAndReleasesAfterFailure() throws Exception {
        var otherFence = new OperationFence(db.dataSource, bean(Phase3Properties.class));
        try (var lease = bean(OperationFence.class).acquire(false)) {
            assertThrows(AccountDeletionException.class, () -> otherFence.acquire(true));
        }
        try (var lease = otherFence.acquire(true)) {
            assertThrows(AccountDeletionException.class, () -> bean(OperationFence.class).acquire(false));
        }
        try (var lease = bean(OperationFence.class).acquire(true)) { lease.assertLive(); }
    }
    @Test void concurrentHttpRequestsNeverExtendDeadlineOrCreateTwoRequests() throws Exception {
        long id = newUser(); String token = login(id);
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            Callable<Integer> call = () -> {
                start.await();
                return mvc.perform(post("/api/account/deletion-request").servletPath("/api/account/deletion-request")
                    .header("Authorization", token).contentType("application/json").content("{\"confirm\":true}"))
                    .andReturn().getResponse().getStatus();
            };
            var a = pool.submit(call); var b = pool.submit(call); start.countDown();
            var codes = List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
            assertTrue(codes.contains(201)); assertTrue(codes.stream().allMatch(c -> Set.of(201,401,503).contains(c)));
        }
        assertEquals(1L, db.jdbc.queryForObject("select count(*) from account_deletion_requests where user_id=?", Long.class, id));
        assertEquals(clock.instant().plus(Duration.ofHours(168)), db.requestFor(id).getScheduledDeletionAt());
    }
    @Test void defaultsAndDurableRolloutLatchFailClosed() throws Exception {
        assertFalse(new Phase3Properties().isEnabled());
        var defaults = new AccountDeletionProperties();
        assertFalse(defaults.isApiEnabled()); assertFalse(defaults.isSchedulerEnabled()); assertFalse(defaults.isDestructiveEnabled());
        assertEquals(Duration.ofHours(168), defaults.getGracePeriod());
        bean(Phase3Properties.class).setEnabled(false);
        assertThrows(IllegalStateException.class, () -> bean(IntegrationReadiness.class).checkStartup());
        status("Bearer nv1_" + "A".repeat(43), 503);
        bean(Phase3Properties.class).setEnabled(true);
        db.jdbc.update("update account_deletion_integration_state set rollout_approved_at=null");
        assertThrows(AccountDeletionException.class, () -> bean(IntegrationReadiness.class).requireReady());
        bean(AccountDeletionProcessor.class).runBatch();
        verifyNoInteractions(storage);
    }
    @Test void defaultOffCompositionPreservesLegacyActiveRoutesWithoutTouchingCloud() throws Exception {
        context.close();
        db.jdbc.update("update account_deletion_integration_state set enforcement_required=false,rollout_approved_at=null");
        db.properties.setApiEnabled(false); db.properties.setSchedulerEnabled(false); db.properties.setDestructiveEnabled(false);
        startContext(false);
        long id = newUser();
        mvc.perform(get("/api/avatar/by-email").servletPath("/api/avatar/by-email").param("email", email(id)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        mvc.perform(post("/api/auth/login").servletPath("/api/auth/login").contentType("application/json")
            .content(json.writeValueAsString(Map.of("email", email(id), "password", "synthetic-password"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.accessToken").doesNotExist());
        status("Bearer ignored", 503);
        bean(AccountDeletionProcessor.class).runBatch();
        verifyNoInteractions(storage);
    }
    @Test void durableManifestAndNewForeignKeysPermitIdempotentUserCleanupOnly() throws Exception {
        long id = newUser(), other = newUser();
        request(login(id)); var plan = plan(id, List.of()); install(plan);
        clock.advance(Duration.ofDays(8));
        process(id);
        assertTrue(users.findById(id).isEmpty()); assertTrue(users.findById(other).isPresent());
        assertEquals("COMPLETED", db.jdbc.queryForObject(
            "select status from account_deletion_requests where id=?", String.class, plan.requestId()));
        assertNull(db.jdbc.queryForObject("select user_id from account_deletion_requests where id=?", Long.class, plan.requestId()));
        assertEquals(0L, db.jdbc.queryForObject("select count(*) from account_deletion_sessions where user_id=?", Long.class, id));
        assertEquals(0L, db.jdbc.queryForObject("select count(*) from account_deletion_cleanup_plans where id=?", Long.class, plan.planId()));
        bean(AccountDeletionProcessor.class).process(plan.requestId());
        verifyNoInteractions(storage);
    }
    @Test void foreignKeyCascadeRemovesPersonalProofButPreservesMinimalAudit() throws Exception {
        long id = newUser(); request(login(id));
        var object = new AccountDeletionSafetyGateway.OwnedObject(id, "synthetic-bucket", "users/" + id + "/avatar/x", 5);
        var plan = plan(id, List.of(object)); install(plan);
        db.jdbc.update("insert into account_deletion_owned_objects(bucket,object_name,user_id,generation,kind) values(?,?,?,?,?)",
            object.bucket(),object.name(),id,5,"AVATAR");
        try (var lease = bean(OperationFence.class).acquire(true)) {
            bean(JdbcCleanupJournal.class).recordVerifiedGenerations(plan.planId(), Set.of(object));
        }
        db.jdbc.update("delete from users where id=?", id); // Synthetic account in the guarded random test schema ONLY.
        for (String table : List.of("account_deletion_owned_objects","account_deletion_cleanup_plans","account_deletion_sessions")) {
            assertEquals(0L, db.jdbc.queryForObject("select count(*) from " + table + " where user_id=?", Long.class, id));
        }
        assertEquals(0L, db.jdbc.queryForObject("select count(*) from account_deletion_generation_receipts where plan_id=?", Long.class, plan.planId()));
        assertNull(db.jdbc.queryForObject("select user_id from account_deletion_requests where id=?", Long.class, plan.requestId()));
    }
    @Test void pendingSharedMeetingsAreNotDeleted() throws Exception {
        long id = newUser(); String token = login(id);
        mvc.perform(post("/api/meeting/create").servletPath("/api/meeting/create").header("Authorization", token)
            .contentType("application/json").content(json.writeValueAsString(Map.of("hostEmail", email(id), "roomCode", "654732"))))
            .andExpect(status().isOk());
        request(token); install(plan(id, List.of()));
        clock.advance(Duration.ofDays(8)); process(id);
        assertEquals(AccountDeletionRequest.Status.FAILED, db.requestFor(id).getStatus());
        assertEquals("MEETING_REVIEW_REQUIRED", db.requestFor(id).getLastError());
        assertTrue(users.findById(id).isPresent());
        assertNotNull(meetings.findByRoomCodeAndStatus("654732","ACTIVE"));
        verifyNoInteractions(storage);
    }
    @Test void missingProviderProofStopsBeforeAnyCloudOperation() throws Exception {
        long id = newUser();
        db.jdbc.update("update users set meshy_task_id='synthetic-task' where id=?", id);
        request(login(id)); install(plan(id,List.of()));
        clock.advance(Duration.ofDays(8)); process(id);
        assertTrue(users.findById(id).isPresent());
        assertEquals("INTEGRATION_REQUIRED", db.requestFor(id).getLastError());
        verifyNoInteractions(storage);
    }
    @Test void completedProviderEvidenceIsBoundToExactPlanAndReference() throws Exception {
        long id = newUser();
        db.jdbc.update("update users set google_id=? where id=?", "synthetic-google-" + id, id);
        request(login(id)); var plan = plan(id,List.of()); install(plan);
        assertFalse(bean(JdbcCleanupJournal.class).externalCleanupCompleted(plan.planId(),
            Phase2Ports.ExternalProvider.GOOGLE_APPLICATION_GRANT));
        db.jdbc.update("""
            insert into account_deletion_provider_receipts(plan_id,provider,reference_hash,completed_at)
            values(?,?,?,now())
            """, plan.planId(), "GOOGLE_APPLICATION_GRANT", JdbcSessionAuthority.digest("synthetic-google-" + id));
        assertTrue(bean(JdbcCleanupJournal.class).externalCleanupCompleted(plan.planId(),
            Phase2Ports.ExternalProvider.GOOGLE_APPLICATION_GRANT));
        clock.advance(Duration.ofDays(8)); process(id);
        assertTrue(users.findById(id).isEmpty());
        assertEquals(0L, db.jdbc.queryForObject("select count(*) from account_deletion_provider_receipts where plan_id=?", Long.class, plan.planId()));
    }
    @Test void incompleteUploadReservationOrOmittedHistoricalObjectBlocksApproval() throws Exception {
        long id = newUser(); request(login(id));
        String name = "users/" + id + "/avatar/historical.glb";
        db.jdbc.update("insert into account_deletion_owned_objects(bucket,object_name,user_id,kind) values(?,?,?,?)",
            "synthetic-bucket",name,id,"AVATAR");
        assertThrows(AccountDeletionException.class, () -> install(plan(id,List.of())));
        db.jdbc.update("update account_deletion_owned_objects set generation=12 where user_id=?", id);
        assertThrows(AccountDeletionException.class, () -> install(plan(id,List.of())));
    }
    @Test void manifestCannotBeUpdatedAndUnknownForeignKeysBlockCleanup() throws Exception {
        long id = newUser(); request(login(id)); var plan=plan(id,List.of()); install(plan);
        assertThrows(org.springframework.dao.DataAccessException.class, () -> db.jdbc.update(
            "update account_deletion_cleanup_plans set plan=plan where id=?", plan.planId()));
        db.jdbc.execute("create table synthetic_unknown_fk(id bigint references users(id))");
        try {
            clock.advance(Duration.ofDays(8)); process(id);
            assertTrue(users.findById(id).isPresent());
            assertEquals("INTEGRATION_REQUIRED", db.requestFor(id).getLastError());
        } finally { db.jdbc.execute("drop table synthetic_unknown_fk"); }
        verifyNoInteractions(storage);
    }
    @Test void transientStorageFailureRetriesUsingDurableGenerationProof() throws Exception {
        long id = newUser();
        var object = new AccountDeletionSafetyGateway.OwnedObject(id, "synthetic-bucket", "users/" + id + "/avatar/x.glb", 11);
        db.jdbc.update("update users set avatar_url=? where id=?", "gs://" + object.bucket() + "/" + object.name(), id);
        request(login(id)); var plan=plan(id,List.of(object)); install(plan);
        Blob blob = mock(Blob.class);
        when(blob.getBucket()).thenReturn(object.bucket()); when(blob.getName()).thenReturn(object.name());
        when(blob.getGeneration()).thenReturn(11L);
        when(blob.getMetadata()).thenReturn(Map.of("nukleusUserId",Long.toString(id),"nukleusExclusiveOwner","true"));
        BlobId blobId=BlobId.of(object.bucket(),object.name(),object.generation());
        java.util.concurrent.atomic.AtomicBoolean present=new java.util.concurrent.atomic.AtomicBoolean(true);
        when(storage.get(blobId)).thenAnswer(call -> present.get()?blob:null);
        java.util.concurrent.atomic.AtomicInteger attempts=new java.util.concurrent.atomic.AtomicInteger();
        when(storage.delete(eq(blobId), any(Storage.BlobSourceOption.class))).thenAnswer(call -> {
            if (attempts.incrementAndGet()==1) throw new StorageException(503,"synthetic temporary failure");
            present.set(false); return true;
        });
        clock.advance(Duration.ofDays(8)); process(id);
        assertEquals(AccountDeletionRequest.Status.FAILED, db.requestFor(id).getStatus());
        assertEquals("STORAGE_FAILURE", db.requestFor(id).getLastError());
        assertTrue(bean(JdbcCleanupJournal.class).generationWasVerified(plan.planId(),object));
        assertEquals(1,db.requestFor(id).getRetryCount());
        clock.advance(Duration.ofHours(1)); process(id);
        assertTrue(users.findById(id).isEmpty()); assertEquals(2,attempts.get());
    }
    @Test void pendingUsersAreDeniedAcrossMeetingAvatarStorageAndUnknownUserRoutes() throws Exception {
        long id = newUser(); request(login(id)); String token = login(id);
        for (String path : List.of("/api/meeting/create","/api/meeting/join","/api/meeting/end",
                "/api/meeting/update-files","/api/meeting/leave","/api/avatar/save",
                "/api/storage/archive-remote-file","/api/future-feature")) {
            mvc.perform(post(path).servletPath(path).header("Authorization",token)
                .contentType("application/json").content("{}")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_PENDING_DELETION"));
        }
        mvc.perform(multipart("/api/storage/upload-image")
            .file(new MockMultipartFile("file","x.png","image/png",new byte[]{1}))
            .servletPath("/api/storage/upload-image").header("Authorization",token))
            .andExpect(status().isForbidden());
        mvc.perform(multipart("/api/storage/upload-meeting-file")
            .file(new MockMultipartFile("file","x.pdf","application/pdf",new byte[]{1}))
            .servletPath("/api/storage/upload-meeting-file").header("Authorization",token))
            .andExpect(status().isForbidden());
        verifyNoInteractions(storage);
    }
    @Test void registrationRemainsAvailableButCannotAssignOwnershipOrProviderIdentity() throws Exception {
        String email = UUID.randomUUID()+"@example.invalid";
        mvc.perform(post("/api/auth/register").servletPath("/api/auth/register").contentType("application/json")
            .content(json.writeValueAsString(Map.of("name","Synthetic Registration","email",email,
                "password","synthetic-password")))).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        User registered = users.findByEmailIgnoreCase(email); assertNotNull(registered);
        assertEquals("ACTIVE",registered.getAccountStatus());
        normal(login(registered.getId()),registered.getId(),200);
        mvc.perform(post("/api/auth/register").servletPath("/api/auth/register").contentType("application/json")
            .content(json.writeValueAsString(Map.of("name","Synthetic Invalid","email",UUID.randomUUID()+"@example.invalid",
                "password","synthetic-password","googleId","unverified-identity")))).andExpect(status().isForbidden());
    }
    @Test void accountCannotLinkAnotherUsersOwnedObjectOrPublishItAsMeetingAsset() throws Exception {
        long owner = newUser(), other = newUser();
        String name = "users/"+owner+"/photo/x";
        db.jdbc.update("insert into account_deletion_owned_objects(bucket,object_name,user_id,generation,kind) values(?,?,?,?,?)",
            "synthetic-bucket",name,owner,3,"PHOTO");
        String url = "https://storage.googleapis.com/synthetic-bucket/"+name;
        mvc.perform(post("/api/avatar/save").servletPath("/api/avatar/save").header("Authorization",login(other))
            .contentType("application/json").content(json.writeValueAsString(Map.of("email",email(other),"imageUrl",url))))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/meeting/create").servletPath("/api/meeting/create").header("Authorization",login(owner))
            .contentType("application/json").content(json.writeValueAsString(
                Map.of("hostEmail",email(owner),"roomCode","728341","pdfUrl",url)))).andExpect(status().isForbidden());
        verifyNoInteractions(storage);
    }
    @Test void failedUploadLeavesReservationThatRequiresReconciliation() throws Exception {
        long id = newUser();
        when(storage.create(any(BlobInfo.class),any(byte[].class),any(Storage.BlobTargetOption[].class)))
            .thenThrow(new StorageException(503,"synthetic outage"));
        mvc.perform(multipart("/api/storage/upload-image")
            .file(new MockMultipartFile("file","x.png","image/png",new byte[]{1}))
            .servletPath("/api/storage/upload-image").header("Authorization",login(id)))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.message").value("PERSONAL_ASSET_UNAVAILABLE"));
        assertEquals(1L,db.jdbc.queryForObject(
            "select count(*) from account_deletion_owned_objects where user_id=? and generation is null",Long.class,id));
    }
    @Test void configuredGraceAndSessionDurationsDoNotChangeExistingDeadlines() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new Phase3Properties().setSessionLifetime(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new Phase3Properties().setSessionLifetime(Duration.ofHours(25)));
        long id = newUser();
        try {
            db.properties.setGracePeriod(Duration.ofHours(192)); request(login(id));
            Instant deadline=db.requestFor(id).getScheduledDeletionAt();
            assertEquals(clock.instant().plus(Duration.ofHours(192)),deadline);
            db.properties.setGracePeriod(Duration.ofHours(168));
            try (var lease=bean(OperationFence.class).acquire(true)) {
                assertEquals(deadline,bean(AccountDeletionService.class).request(id).response().scheduledDeletionAt());
            }
        } finally { db.properties.setGracePeriod(Duration.ofHours(168)); }
    }
    static final class MutableClock extends Clock {
        private volatile Instant now;
        MutableClock(Instant now) { this.now=now; }
        void advance(Duration duration) { now=now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
