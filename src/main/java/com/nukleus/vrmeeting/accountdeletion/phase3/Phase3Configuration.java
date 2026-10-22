package com.nukleus.vrmeeting.accountdeletion.phase3;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.storage.Storage;
import com.nukleus.vrmeeting.accountdeletion.*;
import com.nukleus.vrmeeting.accountdeletion.phase2.*;
import com.nukleus.vrmeeting.repository.*;
import org.springframework.beans.factory.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import javax.sql.DataSource;
import java.time.Clock;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({Phase3Properties.class, CleanupIntegrationProperties.class})
public class Phase3Configuration {
    @Bean ProviderHttp deletionProviderHttp() { return new ProviderHttp(); }
    @Bean ProviderTokenCipher deletionProviderCipher(CleanupIntegrationProperties properties) {
        return new ProviderTokenCipher(properties);
    }
    @Bean com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier deletionGoogleVerifier(
            CleanupIntegrationProperties properties, @Qualifier("accountDeletionClock") Clock clock) {
        return new com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier.Builder(
            new com.google.api.client.http.javanet.NetHttpTransport(), new com.google.api.client.json.gson.GsonFactory())
            .setAudience(properties.getGoogleClientIds()).setClock(clock::millis).setAcceptableTimeSkewSeconds(30).build();
    }
    @Bean GoogleGrantCleanup deletionGoogleCleanup(JdbcTemplate jdbc, UserRepository users,
            CleanupIntegrationProperties properties, OperationFence fence, ProviderHttp http,
            ProviderTokenCipher cipher, ObjectMapper json) {
        return new GoogleGrantCleanup(jdbc, users, properties, fence, http, cipher, json);
    }
    @Bean GoogleReauthentication deletionGoogleReauthentication(JdbcTemplate jdbc,
            com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier verifier,
            JdbcSessionAuthority sessions, UserRepository users, OperationFence fence, GoogleGrantCleanup grants,
            @Qualifier("accountDeletionClock") Clock clock) {
        return new GoogleReauthentication(jdbc, new com.nukleus.vrmeeting.service.GoogleAuthService(verifier),
            sessions, users, fence, grants, clock);
    }
    @Bean MeshyTaskCleanup deletionMeshyCleanup(JdbcTemplate jdbc, CleanupIntegrationProperties properties,
            OperationFence fence, ProviderHttp http, ObjectMapper json, VerifiedProviderAssets assets) {
        return new MeshyTaskCleanup(jdbc, properties, fence, http, json, assets);
    }
    @Bean VerifiedProviderAssets deletionProviderAssets(JdbcTemplate jdbc, OperationFence fence) {
        return new VerifiedProviderAssets(jdbc, fence);
    }
    @Bean CleanupInventoryStore deletionInventoryStore(JdbcTemplate jdbc, UserRepository users,
            OperationFence fence, ObjectMapper json) {
        return new CleanupInventoryStore(jdbc, users, fence, json);
    }
    @Bean LegacyAssetOwnership deletionLegacyOwnership(JdbcTemplate jdbc, ObjectProvider<Storage> storage,
            AccountDeletionProperties properties, OperationFence fence) {
        return new LegacyAssetOwnership(jdbc, storage, fence, properties);
    }
    @Bean RelatedAccountDataCleanup deletionRelatedData(CleanupIntegrationProperties properties,
            JdbcTemplate jdbc, OperationFence fence) {
        return new RelatedAccountDataCleanup(properties, jdbc, fence);
    }
    @Bean CleanupPreparation deletionCleanupPreparation(CleanupIntegrationProperties properties,
            CleanupInventoryStore inventory, JdbcCleanupJournal journal, JdbcTemplate jdbc, OperationFence fence, MeshyTaskCleanup meshy) {
        return new CleanupPreparation(properties, inventory, journal, jdbc, fence, meshy);
    }
    @Bean OperationFence deletionOperationFence(DataSource dataSource, Phase3Properties properties) {
        return new OperationFence(dataSource, properties);
    }
    @Bean IntegrationReadiness deletionIntegrationReadiness(JdbcTemplate jdbc, Phase3Properties properties, CleanupIntegrationProperties cleanup) {
        return new IntegrationReadiness(jdbc, properties, cleanup);
    }
    @Bean SmartInitializingSingleton deletionRolloutLatch(IntegrationReadiness readiness) {
        return readiness::checkStartup;
    }
    @Bean JdbcSessionAuthority deletionSessionAuthority(JdbcTemplate jdbc, UserRepository users,
            AccountDeletionRepository requests, Phase3Properties properties, OperationFence fence,
            @Qualifier("accountDeletionClock") Clock clock) {
        return new JdbcSessionAuthority(jdbc, users, requests, properties, fence, clock);
    }
    @Bean PendingDeletionAccessAdapter deletionPendingAccess(Phase3Properties properties,
            JdbcSessionAuthority sessions, UserRepository users, AccountDeletionRepository requests,
            AccountDeletionProperties deletion, @Qualifier("accountDeletionClock") Clock clock) {
        return new PendingDeletionAccessAdapter(new EndUserIdentityAdapter(
            new Phase2Policy(properties.isEnabled(), true), sessions, users, deletion, clock), requests);
    }
    @Bean AuthenticatedRouteGuard deletionRouteGuard(Phase3Properties properties, PendingDeletionAccessAdapter access,
            UserRepository users, MeetingRepository meetings, JdbcTemplate jdbc, OperationFence fence) {
        return new AuthenticatedRouteGuard(properties, access, users, meetings, jdbc, fence);
    }
    @Bean IntegrationRoutes deletionIntegrationRoutes(Phase3Properties properties, AccountDeletionProperties deletion,
            JdbcSessionAuthority sessions, PendingDeletionAccessAdapter access, OperationFence fence, ObjectMapper json) {
        return new IntegrationRoutes(properties, deletion, sessions, access, fence, json);
    }
    @Bean OwnedUploadRegistry deletionOwnedUploads(JdbcTemplate jdbc, ObjectProvider<Storage> storage,
            AuthenticatedRouteGuard guard, Phase3Properties properties) {
        return new OwnedUploadRegistry(jdbc, storage, guard, properties);
    }
    @Bean JdbcCleanupJournal deletionCleanupJournal(JdbcTemplate jdbc, ObjectMapper json, UserRepository users,
            AccountDeletionRepository requests, PlatformTransactionManager manager, OperationFence fence,
            CleanupInventoryStore inventory) {
        return new JdbcCleanupJournal(jdbc, json, users, requests, manager, fence, inventory);
    }
    @Bean @Primary AccountDeletionSafetyGateway integratedAccountDeletionGateway(Phase3Properties properties,
            AccountDeletionProperties deletion, IntegrationReadiness readiness, JdbcSessionAuthority sessions,
            AccountDeletionRepository requests, JdbcCleanupJournal journal, UserRepository users, JdbcTemplate jdbc,
            ObjectProvider<Storage> storage, @Qualifier("accountDeletionClock") Clock clock,
            CleanupIntegrationProperties cleanup, GoogleGrantCleanup google, MeshyTaskCleanup meshy,
            RelatedAccountDataCleanup related, LegacyAssetOwnership legacy, VerifiedProviderAssets assets) {
        var policy = new Phase2Policy(properties.isEnabled(), true);
        return new Phase2SafetyGateway(policy, deletion, readiness,
            new SessionRevocationAdapter(policy, sessions, requests, clock), journal,
            new PersonalDataInventoryAdapter(users, jdbc, related),
            new StorageOwnershipAdapter(policy, deletion, storage, journal, legacy,
                cleanup.isAutomaticPreparationEnabled() ? assets : null),
            new ExternalReferenceCleanupAdapter(policy, deletion, journal,
                cleanup.isAutomaticPreparationEnabled() ? Map.of(
                    Phase2Ports.ExternalProvider.GOOGLE_APPLICATION_GRANT, google,
                    Phase2Ports.ExternalProvider.MESHY_TASK, meshy) : Map.of()));
    }
}

