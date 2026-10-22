package com.nukleus.vrmeeting.accountdeletion.phase2;

import com.nukleus.vrmeeting.accountdeletion.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Duration;
import java.util.*;
import static com.nukleus.vrmeeting.accountdeletion.phase2.Phase2TestSupport.*;
import static com.nukleus.vrmeeting.accountdeletion.phase2.Phase2Ports.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Phase2CompositionTest {
    @Test void phase2RegistersNoBeansFiltersEndpointsEntitiesOrScheduler() {
        var scanner = new ClassPathScanningCandidateComponentProvider(true);
        assertTrue(scanner.findCandidateComponents("com.nukleus.vrmeeting.accountdeletion.phase2").isEmpty());
        for (Class<?> type : List.of(Phase2SafetyGateway.class, AuthenticatedDeletionFacade.class,
                EndUserIdentityAdapter.class, PendingDeletionAccessAdapter.class,
                PersonalDataInventoryAdapter.class, ApprovedCleanupPlan.class)) {
            assertFalse(type.isAnnotationPresent(jakarta.persistence.Entity.class));
            assertFalse(type.isAnnotationPresent(org.springframework.web.bind.annotation.RestController.class));
        }
    }
    @Test void existingGracePeriodAndDestructiveDefaultsStayUnchanged() {
        var p = new AccountDeletionProperties();
        assertEquals(Duration.ofHours(168), p.getGracePeriod());
        assertFalse(p.isApiEnabled()); assertFalse(p.isSchedulerEnabled()); assertFalse(p.isDestructiveEnabled());
        assertFalse(new Phase2Policy().adaptersEnabled()); assertFalse(new Phase2Policy().cleanupEnabled());
    }
    @Test void disabledGatewayNeverTouchesAnyIntegration() {
        var readiness = mock(ProtectionReadiness.class);
        var revocation = mock(SessionRevocationAdapter.class);
        var journal = mock(CleanupJournal.class);
        var personalData = mock(PersonalDataInventoryAdapter.class);
        var storage = mock(StorageOwnershipAdapter.class);
        var external = mock(ExternalReferenceCleanupAdapter.class);
        var gateway = new Phase2SafetyGateway(new Phase2Policy(), properties(), readiness, revocation,
            journal, personalData, storage, external);
        assertThrows(AccountDeletionException.class, gateway::requireRequestProtection);
        assertThrows(AccountDeletionException.class, () -> gateway.revokeSessions(42));
        assertThrows(AccountDeletionException.class, () -> gateway.prepareCleanup(snapshot()));
        assertThrows(AccountDeletionException.class, () -> gateway.cleanupExternalIdentities(snapshot()));
        verifyNoInteractions(readiness, revocation, journal, personalData, storage, external);
    }
    @Test void allReviewsAndDatabaseChecksPrecedeStorageApproval() {
        var readiness = mock(ProtectionReadiness.class);
        var revocation = mock(SessionRevocationAdapter.class);
        var journal = mock(CleanupJournal.class);
        var personalData = mock(PersonalDataInventoryAdapter.class);
        var storage = mock(StorageOwnershipAdapter.class);
        var external = mock(ExternalReferenceCleanupAdapter.class);
        var plan = plan(snapshot(), List.of(object()));
        when(revocation.requireProcessingFence(42)).thenReturn(11L);
        when(journal.approvedPlan(11, 42)).thenReturn(plan);
        var references = Set.of(new GcsReference(BUCKET, object().name()));
        when(storage.validateReferences(plan, snapshot())).thenReturn(references);
        var gateway = new Phase2SafetyGateway(ENABLED, properties(), readiness, revocation,
            journal, personalData, storage, external);
        assertEquals(plan.objects(), gateway.prepareCleanup(snapshot()).objects());
        var order = inOrder(readiness, revocation, journal, personalData, storage, external);
        order.verify(readiness).requireReady(); order.verify(revocation).requireProcessingFence(42);
        order.verify(journal).approvedPlan(11, 42); order.verify(storage).validateReferences(plan, snapshot());
        order.verify(personalData).requireCurrentAndUnshared(snapshot(), references);
        order.verify(external).preflight(plan, snapshot()); order.verify(storage).verifyAndJournalGenerations(plan);
    }
    @Test void cleanupCannotRunInsideCallerDatabaseTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertThrows(IllegalStateException.class, () -> ENABLED.requireCleanup(properties())); }
        finally { TransactionSynchronizationManager.clear(); }
    }
}
