package com.nukleus.vrmeeting.accountdeletion.phase2;

import com.nukleus.vrmeeting.accountdeletion.*;
import com.nukleus.vrmeeting.accountdeletion.AccountDeletionSafetyGateway.UserSnapshot;
import org.junit.jupiter.api.*;
import java.util.*;
import static com.nukleus.vrmeeting.accountdeletion.phase2.Phase2TestSupport.*;
import static com.nukleus.vrmeeting.accountdeletion.phase2.Phase2Ports.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExternalReferenceCleanupAdapterTest {
    CleanupJournal journal; ExternalReferenceClient google, meshy;
    UserSnapshot user; ApprovedCleanupPlan plan;
    @BeforeEach void setup() {
        journal = mock(CleanupJournal.class); google = mock(ExternalReferenceClient.class);
        meshy = mock(ExternalReferenceClient.class);
        user = new UserSnapshot(42, "synthetic42@example.invalid", List.of(), "synthetic-meshy",
            "synthetic-google-subject", null);
        plan = plan(user, List.of());
    }
    ExternalReferenceCleanupAdapter adapter(Map<ExternalProvider, ExternalReferenceClient> clients) {
        return new ExternalReferenceCleanupAdapter(ENABLED, properties(), journal, clients);
    }
    @Test void unsupportedProviderStopsBeforeAnyDestructiveProviderCall() {
        var adapter = adapter(Map.of(ExternalProvider.GOOGLE_APPLICATION_GRANT, google));
        assertThrows(AccountDeletionException.class, () -> adapter.cleanup(plan, user));
        verify(google, never()).removeApplicationReference(anyLong(), anyString(), any());
    }
    @Test void allProviderPreflightsFinishBeforeFirstRemoval() {
        var adapter = adapter(Map.of(ExternalProvider.GOOGLE_APPLICATION_GRANT, google,
            ExternalProvider.MESHY_TASK, meshy));
        adapter.cleanup(plan, user);
        var order = inOrder(google, meshy, journal);
        order.verify(google).verifyRemovable(42, "synthetic-google-subject");
        order.verify(meshy).verifyRemovable(42, "synthetic-meshy");
        order.verify(google).removeApplicationReference(42, "synthetic-google-subject", plan.planId());
        order.verify(journal).recordExternalCleanupCompleted(plan.planId(), ExternalProvider.GOOGLE_APPLICATION_GRANT);
        order.verify(meshy).removeApplicationReference(42, "synthetic-meshy", plan.planId());
    }
    @Test void durableCompletionReceiptSkipsAlreadyCleanedProvider() {
        when(journal.externalCleanupCompleted(plan.planId(), ExternalProvider.GOOGLE_APPLICATION_GRANT))
            .thenReturn(true);
        adapter(Map.of(ExternalProvider.MESHY_TASK, meshy)).cleanup(plan, user);
        verifyNoInteractions(google); verify(meshy).removeApplicationReference(42, "synthetic-meshy", plan.planId());
    }
    @Test void receiptWriteFailureRetriesWithSameProviderIdempotencyKey() {
        var onlyGoogle = new UserSnapshot(42, user.email(), List.of(), null, user.googleId(), null);
        var googlePlan = plan(onlyGoogle, List.of());
        var adapter = adapter(Map.of(ExternalProvider.GOOGLE_APPLICATION_GRANT, google));
        doThrow(new IllegalStateException("synthetic journal unavailable")).doNothing()
            .when(journal).recordExternalCleanupCompleted(googlePlan.planId(), ExternalProvider.GOOGLE_APPLICATION_GRANT);
        assertThrows(IllegalStateException.class, () -> adapter.cleanup(googlePlan, onlyGoogle));
        adapter.cleanup(googlePlan, onlyGoogle);
        verify(google, times(2)).removeApplicationReference(42, user.googleId(), googlePlan.planId());
    }
    @Test void wrongAccountPlanDoesNotReachProviderClients() {
        var wrong = new UserSnapshot(99, user.email(), List.of(), user.meshyTaskId(), user.googleId(), null);
        assertThrows(AccountDeletionException.class, () -> adapter(Map.of()).cleanup(plan, wrong));
        verifyNoInteractions(google, meshy);
    }
    @Test void noExternalReferencesNeedsNoProviderClient() {
        var empty = new UserSnapshot(42, user.email(), List.of(), null, null, null);
        adapter(Map.of()).cleanup(plan(empty, List.of()), empty);
        verifyNoInteractions(google, meshy, journal);
    }
}
