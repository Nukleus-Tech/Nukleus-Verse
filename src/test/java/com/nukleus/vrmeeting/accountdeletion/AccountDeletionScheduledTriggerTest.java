package com.nukleus.vrmeeting.accountdeletion;

import com.google.api.client.googleapis.auth.oauth2.*;
import com.google.api.client.http.*;
import com.google.api.client.json.gson.GsonFactory;
import com.nukleus.vrmeeting.accountdeletion.phase3.Phase3Properties;
import com.nukleus.vrmeeting.security.JwtUtil;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.junit.jupiter.api.*;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.security.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real RSA verification with local public keys; no Google network or backend server. */
class AccountDeletionScheduledTriggerTest {
    static final String AUDIENCE = "https://synthetic-worker.run.app";
    static final String SUBJECT = "123456789012345678901";
    static final String EMAIL = "deletion-worker@synthetic-project.iam.gserviceaccount.com";
    static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");
    static KeyPair key;
    AccountDeletionTriggerProperties trigger;
    AccountDeletionProperties deletion;
    Phase3Properties integration;
    AccountDeletionProcessor processor;
    AccountDeletionTriggerIdentity identity;
    MockMvc mvc;
    @BeforeAll static void keys() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); key = generator.generateKeyPair();
    }
    static AccountDeletionTriggerProperties properties() {
        var p = new AccountDeletionTriggerProperties();
        p.setEnabled(true); p.setAudience(AUDIENCE); p.setServiceAccountSubject(SUBJECT); p.setServiceAccountEmail(EMAIL);
        return p;
    }
    static GoogleIdTokenVerifier verifier(KeyPair keys, Clock clock) throws Exception {
        HttpTransport transport = new HttpTransport() {
            @Override protected LowLevelHttpRequest buildRequest(String method, String url) {
                throw new AssertionError("Network forbidden in scheduled-trigger tests");
            }
        };
        var manager = spy(new GooglePublicKeysManager(transport, new GsonFactory()));
        doReturn(List.of(keys.getPublic())).when(manager).getPublicKeys();
        return new GoogleIdTokenVerifier.Builder(manager).setAudience(List.of(AUDIENCE))
            .setClock(clock::millis).setAcceptableTimeSkewSeconds(30).build();
    }
    static String signed(KeyPair keys, Instant now) {
        return signed(keys, now, Map.of());
    }
    static String signed(KeyPair keys, Instant now, Map<String,Object> changes) {
        Map<String,Object> claims = new HashMap<>();
        claims.put("iss", "https://accounts.google.com"); claims.put("sub", SUBJECT);
        claims.put("aud", AUDIENCE); claims.put("email", EMAIL); claims.put("email_verified", true);
        claims.put("iat", now.getEpochSecond()); claims.put("exp", now.plusSeconds(3600).getEpochSecond());
        claims.putAll(changes);
        return "Bearer " + Jwts.builder().setClaims(claims).signWith(keys.getPrivate(), SignatureAlgorithm.RS256).compact();
    }
    @BeforeEach void setup() throws Exception {
        trigger = properties(); deletion = new AccountDeletionProperties(); integration = new Phase3Properties();
        deletion.setSchedulerEnabled(true); deletion.setDestructiveEnabled(true); integration.setEnabled(true);
        processor = mock(AccountDeletionProcessor.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        identity = new AccountDeletionTriggerIdentity(trigger, verifier(key, clock), clock);
        mvc = MockMvcBuilders.standaloneSetup(
            new AccountDeletionScheduledTrigger(trigger, deletion, integration, identity, processor)).build();
    }
    @Test void validPinnedServiceIdentityRunsOnlyExistingDueWorker() throws Exception {
        mvc.perform(post(AccountDeletionScheduledTrigger.PATH).header("Authorization", signed(key, NOW)))
            .andExpect(status().isNoContent()).andExpect(header().string("Cache-Control", "no-store"));
        verify(processor).runScheduledBatch(); verifyNoMoreInteractions(processor);
    }
    @Test void unsignedHeadersUserBearerAndAdminJwtCannotTriggerDeletion() throws Exception {
        for (String token : List.of("", "Bearer nv1_" + "a".repeat(43),
                "Bearer " + new JwtUtil().generateToken("admin@example.invalid"), "Bearer a.b.c")) {
            mvc.perform(post(AccountDeletionScheduledTrigger.PATH).header("Authorization", token)
                    .header("X-CloudScheduler", "true").header("X-CloudScheduler-JobName", "trusted-looking"))
                .andExpect(status().isUnauthorized());
        }
        mvc.perform(post(AccountDeletionScheduledTrigger.PATH)).andExpect(status().isUnauthorized());
        verifyNoInteractions(processor);
    }
    @Test void signatureIssuerAudienceSubjectEmailAndTimesAreValidated() throws Exception {
        List<Map<String,Object>> wrong = List.of(
            Map.of("iss", "https://attacker.invalid"), Map.of("aud", "https://other.run.app"),
            Map.of("aud", List.of(AUDIENCE, "https://other.run.app")),
            Map.of("sub", "999999999999999999999"), Map.of("email", "someone@example.invalid"),
            Map.of("email_verified", false), Map.of("exp", NOW.getEpochSecond()),
            Map.of("iat", NOW.plusSeconds(31).getEpochSecond()), Map.of("iat", NOW.minusSeconds(3631).getEpochSecond()),
            Map.of("exp", NOW.plusSeconds(7200).getEpochSecond()), Map.of("nbf", NOW.plusSeconds(31).getEpochSecond()));
        for (Map<String,Object> change : wrong)
            mvc.perform(post(AccountDeletionScheduledTrigger.PATH).header("Authorization", signed(key, NOW, change)))
                .andExpect(status().isUnauthorized());
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        mvc.perform(post(AccountDeletionScheduledTrigger.PATH).header("Authorization", signed(generator.generateKeyPair(), NOW)))
            .andExpect(status().isUnauthorized());
        verifyNoInteractions(processor);
    }
    @Test void noClientCanSelectUserOrOverrideDeadline() throws Exception {
        String bearer = signed(key, NOW);
        mvc.perform(post(AccountDeletionScheduledTrigger.PATH).header("Authorization", bearer)
                .queryParam("userId", "42")).andExpect(status().isBadRequest());
        mvc.perform(post(AccountDeletionScheduledTrigger.PATH).header("Authorization", bearer)
                .contentType("application/json").content("{\"userId\":42,\"force\":true}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post(AccountDeletionScheduledTrigger.PATH).header("Authorization", bearer, bearer))
            .andExpect(status().isBadRequest());
        mvc.perform(get(AccountDeletionScheduledTrigger.PATH).header("Authorization", bearer))
            .andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(processor);
    }
    @Test void eachDefaultOffSwitchPreventsProcessing() throws Exception {
        trigger.setEnabled(false);
        mvc.perform(post(AccountDeletionScheduledTrigger.PATH)).andExpect(status().isServiceUnavailable());
        trigger.setEnabled(true); deletion.setDestructiveEnabled(false);
        mvc.perform(post(AccountDeletionScheduledTrigger.PATH)).andExpect(status().isServiceUnavailable());
        deletion.setDestructiveEnabled(true); deletion.setSchedulerEnabled(false);
        mvc.perform(post(AccountDeletionScheduledTrigger.PATH)).andExpect(status().isServiceUnavailable());
        deletion.setSchedulerEnabled(true); integration.setEnabled(false);
        mvc.perform(post(AccountDeletionScheduledTrigger.PATH)).andExpect(status().isServiceUnavailable());
        verifyNoInteractions(processor);
    }
    @Test void missingMachineConfigurationFailsClosed() throws Exception {
        trigger.setServiceAccountSubject("");
        mvc.perform(post(AccountDeletionScheduledTrigger.PATH).header("Authorization", signed(key, NOW)))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("DELETION_TRIGGER_NOT_CONFIGURED"));
        verifyNoInteractions(processor);
    }
    @Test void failuresReturnRetryable503WithoutSensitiveDetails() throws Exception {
        doThrow(new IllegalStateException("private data must not escape")).when(processor).runScheduledBatch();
        mvc.perform(post(AccountDeletionScheduledTrigger.PATH).header("Authorization", signed(key, NOW)))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("DELETION_WORKER_UNAVAILABLE"));
    }
    @Test void publicKeyFetchFailureNeverAuthorizesCaller() throws Exception {
        var verifier = mock(GoogleIdTokenVerifier.class);
        when(verifier.verify("a.b.c")).thenThrow(new java.io.IOException("sensitive diagnostics"));
        var authority = new AccountDeletionTriggerIdentity(trigger, verifier, Clock.fixed(NOW, ZoneOffset.UTC));
        var error = assertThrows(AccountDeletionException.class, () -> authority.requireAuthorized("Bearer a.b.c"));
        assertEquals("DELETION_TRIGGER_AUTH_UNAVAILABLE", error.getCode());
    }
    @Test void triggerModeDisablesOnlyTheOldBackgroundTimer() {
        var scheduler = new AccountDeletionScheduler(deletion, processor);
        assertTrue(scheduler.isAutoStartup());
        ReflectionTestUtils.setField(scheduler, "trigger", trigger);
        assertFalse(scheduler.isAutoStartup());
        scheduler.start(); assertFalse(scheduler.isRunning());
        trigger.setEnabled(false); assertTrue(scheduler.isAutoStartup());
        verifyNoInteractions(processor);
    }
    @Test void environmentVariablesBindAndDefaultsStayDisabled() {
        assertFalse(new AccountDeletionTriggerProperties().isEnabled());
        assertFalse(new AccountDeletionProperties().isDestructiveEnabled());
        assertFalse(new AccountDeletionProperties().isSchedulerEnabled());
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of(
                "NUKLEUS_ACCOUNTDELETION_TRIGGER_ENABLED", "true",
                "NUKLEUS_ACCOUNTDELETION_TRIGGER_AUDIENCE", AUDIENCE,
                "NUKLEUS_ACCOUNTDELETION_TRIGGER_SERVICEACCOUNTSUBJECT", SUBJECT,
                "NUKLEUS_ACCOUNTDELETION_TRIGGER_SERVICEACCOUNTEMAIL", EMAIL)));
        var bound = new Binder(ConfigurationPropertySources.get(environment))
            .bind("nukleus.account-deletion.trigger", Bindable.of(AccountDeletionTriggerProperties.class)).get();
        assertTrue(bound.isEnabled()); assertTrue(bound.configured()); assertEquals(AUDIENCE, bound.getAudience());
    }
}
