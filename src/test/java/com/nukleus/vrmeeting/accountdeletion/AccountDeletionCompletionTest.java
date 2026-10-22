package com.nukleus.vrmeeting.accountdeletion;

import com.google.api.client.googleapis.auth.oauth2.*;
import com.google.api.client.http.*;
import com.google.api.client.json.gson.GsonFactory;
import com.google.cloud.storage.*;
import com.nukleus.vrmeeting.accountdeletion.phase2.*;
import com.nukleus.vrmeeting.accountdeletion.phase3.*;
import com.nukleus.vrmeeting.controller.GoogleAccountController;
import com.nukleus.vrmeeting.security.JwtUtil;
import io.jsonwebtoken.*;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Reuses the Phase 3 harness, real PG and real MVC/security/lifecycle beans. Providers NEVER use a network. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccountDeletionCompletionTest {
    static final String CLIENT = "synthetic-client.apps.googleusercontent.com";
    static final String EVIDENCE = "a".repeat(64);
    KeyPair key;
    ProviderHttp http;
    final Phase3IntegrationTest h = new Phase3IntegrationTest() {
        @Override void startContext(boolean enabled) {
            context = new AnnotationConfigWebApplicationContext();
            context.setServletContext(new MockServletContext());
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                "nukleus.account-deletion.integration.enabled=" + enabled,
                "nukleus.account-deletion.cleanup.automatic-preparation-enabled=true",
                "nukleus.account-deletion.cleanup.anonymize-meeting-references=true",
                "nukleus.account-deletion.cleanup.provider-calls-enabled=true",
                "nukleus.account-deletion.cleanup.google-client-ids=" + CLIENT,
                "nukleus.account-deletion.cleanup.meshy-api-key=synthetic-never-sent",
                "nukleus.account-deletion.cleanup.encryption-key=" + Base64.getEncoder().encodeToString(new byte[32]));
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
                factory.registerSingleton("deletionProviderHttp", http);
                factory.registerSingleton("deletionGoogleVerifier", verifier());
            });
            context.register(WebConfiguration.class, GoogleAccountController.class);
            context.refresh();
            mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
        }
    };
    @BeforeAll void database() throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); key=generator.generateKeyPair();
        h.database();
        try(var stream=getClass().getResourceAsStream("/db/account-deletion/V20261008_03__cleanup_evidence_and_google_reauthentication.sql");
            var c=h.db.dataSource.getConnection();var s=c.createStatement()){
            assertNotNull(stream);s.execute(new String(stream.readAllBytes(),StandardCharsets.UTF_8));
        }
    }
    @BeforeEach void setup(){
        http=mock(ProviderHttp.class,call -> {
            if(call.getMethod().getName().equals("exchange"))throw new AssertionError("Unexpected provider call");
            return RETURNS_DEFAULTS.answer(call);
        });
        h.setup();
    }
    @AfterEach void close(){h.close();}
    @AfterAll void cleanup(){h.cleanup();}
    GoogleIdTokenVerifier verifier(){
        try {
            HttpTransport transport=new HttpTransport(){
                @Override protected LowLevelHttpRequest buildRequest(String method,String url){
                    throw new AssertionError("No live Google network permitted");
                }
            };
            var manager=spy(new GooglePublicKeysManager(transport,new GsonFactory()));
            doReturn(List.of(key.getPublic())).when(manager).getPublicKeys();
            return new GoogleIdTokenVerifier.Builder(manager).setAudience(Set.of(CLIENT))
                .setClock(h.clock::millis).setAcceptableTimeSkewSeconds(30).build();
        }catch(Exception e){throw new AssertionError(e);}
    }
    String challenge() throws Exception {
        var response=h.mvc.perform(post("/api/auth/google-challenge").servletPath("/api/auth/google-challenge"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse();
        return h.json.readTree(response.getContentAsString()).get("nonce").asText();
    }
    String signed(long id,String nonce,String audience,String issuer,PrivateKey signing,Instant issued,boolean emailVerified){
        return Jwts.builder().setSubject("google-"+id).setAudience(audience).setIssuer(issuer)
            .setIssuedAt(Date.from(issued)).setExpiration(Date.from(issued.plusSeconds(3600)))
            .claim("email",h.email(id)).claim("email_verified",emailVerified).claim("nonce",nonce)
            .signWith(signing,SignatureAlgorithm.RS256).compact();
    }
    String googleLogin(long id) throws Exception {
        return exchange(signed(id,challenge(),CLIENT,"https://accounts.google.com",key.getPrivate(),h.clock.instant(),true),200);
    }
    String exchange(String token,int statusCode) throws Exception {
        var result=h.mvc.perform(post("/api/auth/google").servletPath("/api/auth/google").contentType("application/json")
            .content(h.json.writeValueAsString(Map.of("idToken",token)))).andExpect(status().is(statusCode)).andReturn();
        return statusCode==200 ? "Bearer "+h.json.readTree(result.getResponse().getContentAsString()).get("accessToken").asText() : "";
    }
    long googleUser(){
        long id=h.newUser();
        h.db.jdbc.update("update users set password=null,google_id=? where id=?","google-"+id,id);return id;
    }
    void review(long id){
        try(var fence=h.bean(OperationFence.class).acquire(true)){
            h.bean(CleanupInventoryStore.class).reviewCoverage(id,EVIDENCE,EnumSet.allOf(ApprovedCleanupPlan.ReviewDomain.class));
        }
    }
    UUID task(long id,String kind,boolean current){
        UUID task=UUID.randomUUID();
        if(current)h.db.jdbc.update("update users set meshy_task_id=? where id=?",task.toString(),id);
        try(var fence=h.bean(OperationFence.class).acquire(true)){
            h.bean(CleanupInventoryStore.class).recordVerifiedMeshyTask(id,kind,task,EVIDENCE);
        }
        return task;
    }
    URI endpoint(String kind,UUID task){return URI.create("https://api.meshy.ai/openapi/v1/"+kind+"/"+task);}
    void successfulTask(String kind,UUID task){
        doReturn(new ProviderHttp.Result(200,"{\"id\":\""+task+"\",\"status\":\"SUCCEEDED\"}"))
            .when(http).exchange(eq("GET"),eq(endpoint(kind,task)),anyMap(),isNull());
        doAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals("Bearer synthetic-never-sent",((Map<?,?>)call.getArgument(2)).get("Authorization"));
            return new ProviderHttp.Result(200,"");})
            .when(http).exchange(eq("DELETE"),eq(endpoint(kind,task)),anyMap(),isNull());
    }
    AccountDeletionSafetyGateway.OwnedObject object(long id,boolean legacy){
        var object=new AccountDeletionSafetyGateway.OwnedObject(id,"synthetic-bucket",
            legacy?"images/glb/"+UUID.randomUUID()+".glb":"users/"+id+"/photo/"+UUID.randomUUID()+".png",19);
        Blob blob=mock(Blob.class);
        when(blob.getBucket()).thenReturn(object.bucket());when(blob.getName()).thenReturn(object.name());
        when(blob.getGeneration()).thenReturn(object.generation());
        when(blob.getMetadata()).thenReturn(legacy?null:Map.of("nukleusUserId",Long.toString(id),"nukleusExclusiveOwner","true"));
        var blobId=BlobId.of(object.bucket(),object.name(),object.generation());
        AtomicBoolean present=new AtomicBoolean(true);
        when(h.storage.get(blobId)).thenAnswer(call->present.get()?blob:null);
        when(h.storage.delete(eq(blobId),any(Storage.BlobSourceOption.class))).thenAnswer(call->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());present.set(false);return true;
        });
        if(legacy){
            try(var fence=h.bean(OperationFence.class).acquire(true)){
                h.bean(LegacyAssetOwnership.class).verifyAndRecord(object,EVIDENCE);
            }
        }else h.db.jdbc.update("insert into account_deletion_owned_objects(bucket,object_name,user_id,generation,kind) values(?,?,?,?,?)",
            object.bucket(),object.name(),id,object.generation(),"PHOTO");
        return object;
    }
    long request(long id,String token) throws Exception {h.request(token);return h.db.requestFor(id).getId();}
    void due(long id){h.clock.advance(Duration.ofDays(7));h.process(id);}
    void completed(long id,long requestId){
        assertTrue(h.users.findById(id).isEmpty());
        assertEquals("COMPLETED",h.db.jdbc.queryForObject("select status from account_deletion_requests where id=?",String.class,requestId));
        assertNull(h.db.jdbc.queryForObject("select user_id from account_deletion_requests where id=?",Long.class,requestId));
    }

    @Test void googleOnlyLifecycleRevokesRestrictsCancelsAndFreshReauthenticationRestoresAccess() throws Exception {
        long id=googleUser();String token=googleLogin(id);
        long request=request(id,token);
        assertEquals(h.clock.instant().plus(Duration.ofHours(168)),h.db.requestFor(id).getScheduledDeletionAt());
        h.status(token,401);String restricted=googleLogin(id);
        h.status(restricted,200);h.normal(restricted,id,403);h.cancel(restricted,200);
        h.status(token,401);h.normal(restricted,id,403);h.normal(googleLogin(id),id,200);
        assertEquals("CANCELLED",h.db.jdbc.queryForObject("select status from account_deletion_requests where id=?",String.class,request));
        assertNull(h.users.findById(id).orElseThrow().getPassword());
        verifyNoInteractions(http,h.storage);
    }
    @Test void googleNonceReplayWrongAudienceIssuerSignatureEmailAndOldTokensAreRejected() throws Exception {
        long id=googleUser();String nonce=challenge();
        String token=signed(id,nonce,CLIENT,"https://accounts.google.com",key.getPrivate(),h.clock.instant(),true);
        exchange(token,200);exchange(token,401);
        for(var claims:List.of(new String[]{"wrong-client","https://accounts.google.com"},
                new String[]{CLIENT,"https://attacker.invalid"})){
            exchange(signed(id,challenge(),claims[0],claims[1],key.getPrivate(),h.clock.instant(),true),401);
        }
        KeyPairGenerator gen=KeyPairGenerator.getInstance("RSA");gen.initialize(2048);
        exchange(signed(id,challenge(),CLIENT,"https://accounts.google.com",gen.generateKeyPair().getPrivate(),h.clock.instant(),true),401);
        exchange(signed(id,challenge(),CLIENT,"https://accounts.google.com",key.getPrivate(),h.clock.instant(),false),401);
        exchange(signed(id,challenge(),CLIENT,"https://accounts.google.com",key.getPrivate(),h.clock.instant().minusSeconds(301),true),401);
        exchange(signed(id,null,CLIENT,"https://accounts.google.com",key.getPrivate(),h.clock.instant(),true),401);
        exchange(new JwtUtil().generateToken(h.email(id)),401);
        verifyNoInteractions(http);
    }
    @Test void googleNeverLinksAnEmailToAnUnverifiedSubjectAndBlockedAccountsRemainBlocked() throws Exception {
        long unlinked=h.newUser();
        exchange(signed(unlinked,challenge(),CLIENT,"https://accounts.google.com",key.getPrivate(),h.clock.instant(),true),401);
        assertNull(h.users.findById(unlinked).orElseThrow().getGoogleId());
        long id=googleUser();h.db.jdbc.update("update users set account_status='BLOCKED' where id=?",id);
        exchange(signed(id,challenge(),CLIENT,"https://accounts.google.com",key.getPrivate(),h.clock.instant(),true),403);
    }
    @Test void googleOnlyExpiredRequestCannotBeCancelledAndCompletesLocalIdTokenLinkCleanup() throws Exception {
        long id=googleUser();long request=request(id,googleLogin(id));review(id);
        h.clock.advance(Duration.ofDays(7));h.cancel(googleLogin(id),409);h.process(id);
        completed(id,request);
        assertEquals(0L,h.db.jdbc.queryForObject("select count(*) from account_deletion_google_links where user_id=?",Long.class,id));
        verifyNoInteractions(http,h.storage);
    }
    @Test void completePasswordLifecycleDeletesCurrentHistoricalLegacyObjectsOtpAndPersonalProofOnly() throws Exception {
        long id=h.newUser(),other=h.newUser();String email=h.email(id);
        var photo=object(id,false);var legacy=object(id,true);
        h.db.jdbc.update("update users set image_url=?,avatar_url=? where id=?",
            "gs://"+photo.bucket()+"/"+photo.name(),"gs://"+legacy.bucket()+"/"+legacy.name(),id);
        h.db.jdbc.update("insert into email_otp(email,otp,expiry_time) values(?,?,now()),(?,?,now())",
            email,"111111",h.email(other),"222222");
        long request=request(id,h.login(id));review(id);due(id);completed(id,request);
        h.bean(AccountDeletionProcessor.class).process(request);
        assertTrue(h.users.findById(other).isPresent());
        assertEquals(0L,h.db.jdbc.queryForObject("select count(*) from email_otp where email=?",Long.class,email));
        assertEquals(1L,h.db.jdbc.queryForObject("select count(*) from email_otp where email=?",Long.class,h.email(other)));
        for(String table:List.of("account_deletion_sessions","account_deletion_owned_objects","account_deletion_legacy_objects",
                "account_deletion_coverage","account_deletion_cleanup_plans"))
            assertEquals(0L,h.db.jdbc.queryForObject("select count(*) from "+table+" where user_id=?",Long.class,id));
        verify(h.storage,times(2)).delete(any(BlobId.class),any(Storage.BlobSourceOption.class));
        verifyNoInteractions(http);
    }
    @Test void providerRetryAfterStorageDeletionUsesReceiptsAndResumesWithoutDeletingAgain() throws Exception {
        long id=h.newUser();var photo=object(id,false);UUID task=task(id,"image-to-3d",true);successfulTask("image-to-3d",task);
        AtomicInteger attempts=new AtomicInteger();
        doAnswer(call->new ProviderHttp.Result(attempts.incrementAndGet()==1?503:200,"")).when(http)
            .exchange(eq("DELETE"),eq(endpoint("image-to-3d",task)),anyMap(),isNull());
        long request=request(id,h.login(id));review(id);due(id);
        assertEquals(AccountDeletionRequest.Status.FAILED,h.db.requestFor(id).getStatus());
        assertNotNull(h.db.requestFor(id).getNextAttemptAt());
        h.clock.advance(Duration.ofHours(1));h.process(id);completed(id,request);
        assertEquals(2,attempts.get());
        verify(h.storage,times(1)).delete(eq(BlobId.of(photo.bucket(),photo.name(),photo.generation())),any(Storage.BlobSourceOption.class));
    }
    @Test void allMeshyKindsAreDeletedChildrenFirstIncludingHistoricalTasksWithoutCurrentId() throws Exception {
        long id=h.newUser();List<UUID> tasks=new ArrayList<>();
        List<String> kinds=List.of("animations","rigging","image-to-3d","image-to-image");
        for(String kind:kinds){UUID task=task(id,kind,false);tasks.add(task);successfulTask(kind,task);}
        long request=request(id,h.login(id));review(id);due(id);completed(id,request);
        var order=inOrder(http);
        for(int i=0;i<tasks.size();i++)order.verify(http).exchange(eq("DELETE"),eq(endpoint(kinds.get(i),tasks.get(i))),anyMap(),isNull());
    }
    @Test void unverified404AndInProgressTaskNeverDeletePersonalAssets() throws Exception {
        long id=h.newUser();object(id,false);UUID task=task(id,"image-to-3d",true);
        doReturn(new ProviderHttp.Result(404,"")).when(http).exchange(eq("GET"),any(),anyMap(),isNull());
        request(id,h.login(id));review(id);due(id);
        assertEquals("INTEGRATION_REQUIRED",h.db.requestFor(id).getLastError());
        verify(h.storage,never()).delete(any(BlobId.class),any(Storage.BlobSourceOption.class));
        doReturn(new ProviderHttp.Result(200,"{\"id\":\""+task+"\",\"status\":\"IN_PROGRESS\"}"))
            .when(http).exchange(eq("GET"),any(),anyMap(),isNull());
        try(var fence=h.bean(OperationFence.class).acquire(true)){
            h.bean(CleanupPreparation.class).retryReviewedFailure(h.db.requestFor(id).getId(),h.db.properties,h.clock);
        }
        h.process(id);assertNotNull(h.db.requestFor(id).getNextAttemptAt());
        verify(h.storage,never()).delete(any(BlobId.class),any(Storage.BlobSourceOption.class));
    }
    @Test void provedTaskMissingAfterCrashIsIdempotentlyCompleted() throws Exception {
        long id=h.newUser();UUID task=task(id,"rigging",false);successfulTask("rigging",task);
        try(var fence=h.bean(OperationFence.class).acquire(true)){h.bean(MeshyTaskCleanup.class).verifyRemovable(id,"");}
        doReturn(new ProviderHttp.Result(404,"")).when(http).exchange(eq("GET"),any(),anyMap(),isNull());
        doReturn(new ProviderHttp.Result(404,"")).when(http).exchange(eq("DELETE"),any(),anyMap(),isNull());
        long request=request(id,h.login(id));review(id);due(id);completed(id,request);
    }
    @Test void oauthApplicationGrantIsEncryptedAndRevocationRetriesWithoutDeletingGoogleAccount() throws Exception {
        long id=googleUser();String token=googleLogin(id);
        try(var fence=h.bean(OperationFence.class).acquire(true)){
            h.bean(GoogleGrantCleanup.class).recordVerifiedGrant(id,"google-"+id,CLIENT,"synthetic-refresh");
        }
        byte[] ciphertext=h.db.jdbc.queryForObject("select encrypted_token from account_deletion_google_links where user_id=?",byte[].class,id);
        assertFalse(new String(ciphertext,StandardCharsets.UTF_8).contains("synthetic-refresh"));
        AtomicInteger calls=new AtomicInteger();
        doAnswer(call->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals("token=synthetic-refresh",call.getArgument(3));
            return new ProviderHttp.Result(calls.incrementAndGet()==1?503:200,"");
        }).when(http).exchange(eq("POST"),eq(URI.create("https://oauth2.googleapis.com/revoke")),anyMap(),anyString());
        long request=request(id,token);review(id);due(id);assertTrue(h.users.findById(id).isPresent());
        h.clock.advance(Duration.ofHours(1));h.process(id);completed(id,request);assertEquals(2,calls.get());
    }
    @Test void cipherRejectsWrongUserTamperingAndMissingKey(){
        var cipher=h.bean(ProviderTokenCipher.class);byte[] bytes=cipher.encrypt(123,"synthetic-sub","synthetic-token");
        assertEquals("synthetic-token",cipher.decrypt(123,"synthetic-sub",bytes));
        assertThrows(AccountDeletionException.class,()->cipher.decrypt(124,"synthetic-sub",bytes));
        bytes[bytes.length-1]^=1;
        assertThrows(AccountDeletionException.class,()->cipher.decrypt(123,"synthetic-sub",bytes));
        h.bean(CleanupIntegrationProperties.class).setEncryptionKey("");
        assertThrows(AccountDeletionException.class,()->cipher.encrypt(123,"synthetic-sub","synthetic-token"));
    }
    @Test void sharedEndedMeetingsKeepRowsAssetsAndOtherMembersButLoseDeletingIdentity() throws Exception {
        long id=h.newUser(),other=h.newUser();String email=h.email(id);
        String meeting=UUID.randomUUID().toString();
        h.db.jdbc.update("""
            insert into meetings(meeting_id,room_code,host_email,status,participant_emails,pdf_url)
            values(?,?,?,'ENDED',?,?)
            """,meeting,"complete-test",email,email+","+h.email(other),"gs://synthetic-bucket/meetings/shared.pdf");
        h.db.jdbc.update("update users set current_meeting_id=? where id=?",meeting,id);
        long request=request(id,h.login(id));review(id);due(id);completed(id,request);
        var row=h.db.jdbc.queryForMap("select * from meetings where meeting_id=?",meeting);
        assertTrue(row.get("host_email").toString().matches("deleted-[a-f0-9-]+@account.invalid"));
        assertEquals(h.email(other),row.get("participant_emails"));
        assertEquals("gs://synthetic-bucket/meetings/shared.pdf",row.get("pdf_url"));
        assertEquals("ENDED",row.get("status"));verifyNoInteractions(http,h.storage);
    }
    @Test void activeHostMeetingBlocksCleanupUntilReviewedResolution() throws Exception {
        long id=h.newUser();String meeting=UUID.randomUUID().toString();
        h.db.jdbc.update("insert into meetings(meeting_id,room_code,host_email,status) values(?,?,?,'ACTIVE')",meeting,"active-test",h.email(id));
        request(id,h.login(id));review(id);due(id);
        assertEquals("MEETING_REVIEW_REQUIRED",h.db.requestFor(id).getLastError());
        assertEquals("ACTIVE",h.db.jdbc.queryForObject("select status from meetings where meeting_id=?",String.class,meeting));
        verifyNoInteractions(http,h.storage);
    }
    @Test void sharedLegacyAliasCannotBeDeletedEvenWithImportedOwnershipEvidence() throws Exception {
        long id=h.newUser(),other=h.newUser();var object=object(id,true);
        h.db.jdbc.update("update users set avatar_url=? where id=?","https://storage.googleapis.com/"+object.bucket()+"/"+object.name()+"?signature=synthetic",other);
        request(id,h.login(id));review(id);due(id);
        assertEquals("STORAGE_OWNERSHIP_UNVERIFIED",h.db.requestFor(id).getLastError());
        verify(h.storage,never()).delete(any(BlobId.class),any(Storage.BlobSourceOption.class));
    }
    @Test void missingCoverageAndChangedRegistryCannotProduceOrBroadenAPlan() throws Exception {
        long id=h.newUser();request(id,h.login(id));due(id);
        assertEquals("INTEGRATION_REQUIRED",h.db.requestFor(id).getLastError());
        review(id);object(id,false);
        try(var fence=h.bean(OperationFence.class).acquire(true)){
            h.bean(CleanupPreparation.class).retryReviewedFailure(h.db.requestFor(id).getId(),h.db.properties,h.clock);
        }
        h.process(id);assertEquals("INTEGRATION_REQUIRED",h.db.requestFor(id).getLastError());
        verify(h.storage,never()).delete(any(BlobId.class),any(Storage.BlobSourceOption.class));
    }
    @Test void unknownForeignKeyStillBlocksBeforeStorageAndNewProofTablesCascade() throws Exception {
        long id=h.newUser();object(id,false);request(id,h.login(id));review(id);
        h.db.jdbc.execute("create table synthetic_completion_fk(user_id bigint references users(id))");
        try{due(id);assertEquals("INTEGRATION_REQUIRED",h.db.requestFor(id).getLastError());}
        finally{h.db.jdbc.execute("drop table synthetic_completion_fk");}
        verify(h.storage,never()).delete(any(BlobId.class),any(Storage.BlobSourceOption.class));
    }
    @Test void simultaneousWorkersDeleteEachGenerationOnce() throws Exception {
        long id=h.newUser();var object=object(id,false);long request=request(id,h.login(id));review(id);
        h.clock.advance(Duration.ofDays(7));
        try(var pool=Executors.newFixedThreadPool(2)){
            var start=new CountDownLatch(1);
            Callable<Void> call=()->{start.await();h.bean(AccountDeletionProcessor.class).process(request);return null;};
            var a=pool.submit(call);var b=pool.submit(call);start.countDown();a.get(20,TimeUnit.SECONDS);b.get(20,TimeUnit.SECONDS);
        }
        completed(id,request);
        verify(h.storage,times(1)).delete(eq(BlobId.of(object.bucket(),object.name(),object.generation())),any(Storage.BlobSourceOption.class));
    }
    @Test void defaultFlagsAndPasswordCompatibilityIncludingLongPasswords(){
        var defaults=new CleanupIntegrationProperties();
        assertFalse(defaults.isAutomaticPreparationEnabled());assertFalse(defaults.isProviderCallsEnabled());
        assertFalse(defaults.isAnonymizeMeetingReferences());assertTrue(defaults.getGoogleClientIds().isEmpty());
        String password="very-long-synthetic-password".repeat(8),encoded=PasswordCredentials.encode(password);
        assertTrue(PasswordCredentials.matches(password,encoded));assertFalse(PasswordCredentials.matches(password+"x",encoded));
        assertTrue(PasswordCredentials.matches("legacy","legacy"));assertFalse(PasswordCredentials.matches(null,"legacy"));
        assertFalse(PasswordCredentials.matches("anything",null));
    }
    @Test void providerOutputUrlsAreVerifiedFromTheOwnedTaskRatherThanTreatedAsGcsObjects() throws Exception {
        long id=h.newUser();UUID task=task(id,"rigging",false);successfulTask("rigging",task);
        String path="https://assets.meshy.ai/synthetic/tasks/"+task+"/output/avatar.glb";
        doReturn(new ProviderHttp.Result(200,"{\"id\":\""+task+"\",\"status\":\"SUCCEEDED\",\"result\":{\"rigged_character_glb_url\":\""+path+"?Expires=1\"}}"))
            .when(http).exchange(eq("GET"),eq(endpoint("rigging",task)),anyMap(),isNull());
        h.db.jdbc.update("update users set rigged_glb_url=? where id=?",path+"?Expires=2",id);
        long request=request(id,h.login(id));review(id);due(id);completed(id,request);
        verifyNoInteractions(h.storage);
        assertEquals(0L,h.db.jdbc.queryForObject("select count(*) from account_deletion_provider_assets where task_id=?",Long.class,task));
        assertEquals(0L,h.db.jdbc.queryForObject("select count(*) from account_deletion_provider_tasks where user_id=?",Long.class,id));
    }
    @Test void sharedProviderOutputsBlockDeletionAndArbitraryRemoteUrlsCannotBeAdopted() throws Exception {
        long id=h.newUser(),other=h.newUser();UUID task=task(id,"image-to-3d",true);successfulTask("image-to-3d",task);
        String path="https://assets.meshy.ai/synthetic/tasks/"+task+"/output/avatar.glb";
        doReturn(new ProviderHttp.Result(200,"{\"id\":\""+task+"\",\"status\":\"SUCCEEDED\",\"model_urls\":{\"glb\":\""+path+"?Expires=1\"}}"))
            .when(http).exchange(eq("GET"),eq(endpoint("image-to-3d",task)),anyMap(),isNull());
        h.db.jdbc.update("update users set avatar_url=? where id=?",path+"?Expires=2",other);
        request(id,h.login(id));review(id);due(id);
        assertEquals("STORAGE_OWNERSHIP_UNVERIFIED",h.db.requestFor(id).getLastError());
        verify(http,never()).exchange(eq("DELETE"),any(),anyMap(),isNull());
        assertFalse(h.bean(VerifiedProviderAssets.class).approved(id,"https://attacker.invalid/object"));
        assertFalse(h.bean(VerifiedProviderAssets.class).approved(id,path+"-unrelated"));
    }
    @Test void legacyVersionedGenerationsAreIndividuallyVerifiedAndDeleted() throws Exception {
        long id=h.newUser();var first=object(id,true);
        var second=new AccountDeletionSafetyGateway.OwnedObject(id,first.bucket(),first.name(),20);
        var secondId=BlobId.of(second.bucket(),second.name(),20L);
        Blob blob=mock(Blob.class);when(blob.getBucket()).thenReturn(second.bucket());
        when(blob.getName()).thenReturn(second.name());when(blob.getGeneration()).thenReturn(20L);
        AtomicBoolean present=new AtomicBoolean(true);
        when(h.storage.get(secondId)).thenAnswer(call->present.get()?blob:null);
        when(h.storage.delete(eq(secondId),any(Storage.BlobSourceOption.class))).thenAnswer(call->{present.set(false);return true;});
        try(var fence=h.bean(OperationFence.class).acquire(true)){h.bean(LegacyAssetOwnership.class).verifyAndRecord(second,EVIDENCE);}
        long request=request(id,h.login(id));review(id);due(id);completed(id,request);
        verify(h.storage).delete(eq(BlobId.of(first.bucket(),first.name(),19L)),any(Storage.BlobSourceOption.class));
        verify(h.storage).delete(eq(secondId),any(Storage.BlobSourceOption.class));
    }
    @Test void interruptedUploadCanBeReconciledOnlyWithExactReservedGenerationAndOwnership() throws Exception {
        long id=h.newUser();var object=object(id,false);
        h.db.jdbc.update("update account_deletion_owned_objects set generation=null where user_id=?",id);
        try(var fence=h.bean(OperationFence.class).acquire(true)){
            assertThrows(AccountDeletionException.class,()->h.bean(LegacyAssetOwnership.class).reconcileReservedUpload(
                new AccountDeletionSafetyGateway.OwnedObject(id+1,object.bucket(),object.name(),object.generation())));
            h.bean(LegacyAssetOwnership.class).reconcileReservedUpload(object);
        }
        long request=request(id,h.login(id));review(id);due(id);completed(id,request);
    }
    @Test void loginBookkeepingCannotOverwriteAnAdminBlockCommittedAfterCredentialRead(){
        long id=h.newUser();String email=h.email(id);
        var repo=mock(com.nukleus.vrmeeting.repository.UserRepository.class,
            org.mockito.AdditionalAnswers.delegatesTo(h.users));
        doAnswer(call->{
            var snapshot=h.users.findByEmailIgnoreCase(email);
            h.db.jdbc.update("update users set account_status='BLOCKED' where id=?",id);
            return snapshot;
        }).when(repo).findByEmailIgnoreCase(email);
        var sessions=new JdbcSessionAuthority(h.db.jdbc,repo,h.db.repository,h.bean(Phase3Properties.class),
            h.bean(OperationFence.class),h.clock);
        Map<String,Object> response;
        try(var fence=h.bean(OperationFence.class).acquire(false)){
            response=sessions.login(email,"synthetic-password");
        }
        assertEquals("BLOCKED",h.users.findById(id).orElseThrow().getAccountStatus());
        assertThrows(AccountDeletionException.class,()->sessions.authenticate("Bearer "+response.get("accessToken")));
    }
    @Test void anotherUserReferencingAHistoricalTaskBlocksProviderDeletion() throws Exception {
        long id=h.newUser(),other=h.newUser();UUID task=task(id,"image-to-3d",false);
        h.db.jdbc.update("update users set meshy_task_id=? where id=?",task.toString(),other);
        request(id,h.login(id));review(id);due(id);
        assertEquals("INTEGRATION_REQUIRED",h.db.requestFor(id).getLastError());
        verifyNoInteractions(http,h.storage);
    }
}
