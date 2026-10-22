package com.nukleus.vrmeeting.accountdeletion.phase2;

import com.nukleus.vrmeeting.accountdeletion.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import static com.nukleus.vrmeeting.accountdeletion.phase2.Phase2TestSupport.*;
import static com.nukleus.vrmeeting.accountdeletion.phase2.Phase2Ports.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionRevocationAdapterTest {
    SessionAuthority sessions; AccountDeletionRepository requests; SessionRevocationAdapter adapter;
    @BeforeEach void setup() {
        sessions = mock(SessionAuthority.class); requests = mock(AccountDeletionRepository.class);
        adapter = new SessionRevocationAdapter(ENABLED, sessions, requests, CLOCK);
    }
    void state(AccountDeletionRequest.Status status) {
        when(requests.findFirstByUserIdOrderByRequestedAtDescIdDesc(42L)).thenReturn(Optional.of(request(status)));
    }
    @Test void defaultDisabledDoesNotRevoke() {
        adapter = new SessionRevocationAdapter(new Phase2Policy(), sessions, requests, CLOCK);
        assertThrows(AccountDeletionException.class, () -> adapter.revokeForPendingRequest(42));
        verifyNoInteractions(sessions, requests);
    }
    @Test void retriesUseSameDurableRequestKeyAndVerifiedOwner() {
        state(AccountDeletionRequest.Status.PENDING);
        adapter.revokeForPendingRequest(42); adapter.revokeForPendingRequest(42);
        verify(sessions, times(2)).revokeAll(42, 11);
        verifyNoMoreInteractions(sessions);
    }
    @Test void cancelledCallbackDoesNotRevokeNewSessions() {
        state(AccountDeletionRequest.Status.CANCELLED); adapter.revokeForPendingRequest(42);
        verifyNoInteractions(sessions);
    }
    @Test void noClientIdOrWrongRepositoryOwnerCanRevokeAnotherUser() {
        assertThrows(AccountDeletionException.class, () -> adapter.revokeForPendingRequest(99));
        var wrong = request(AccountDeletionRequest.Status.PENDING);
        ReflectionTestUtils.setField(wrong, "userId", 99L);
        when(requests.findFirstByUserIdOrderByRequestedAtDescIdDesc(42L)).thenReturn(Optional.of(wrong));
        assertThrows(AccountDeletionException.class, () -> adapter.revokeForPendingRequest(42));
        verifyNoInteractions(sessions);
    }
    @Test void processingRequiresCompletedRevocationAndDurableFence() {
        state(AccountDeletionRequest.Status.PROCESSING);
        assertEquals(11, adapter.requireProcessingFence(42));
        var order = inOrder(sessions);
        order.verify(sessions).revokeAll(42, 11); order.verify(sessions).requireDurableWriteFence(42, 11);
    }
    @Test void pendingOrFutureDeadlineCannotEnterPermanentCleanup() {
        state(AccountDeletionRequest.Status.PENDING);
        assertThrows(AccountDeletionException.class, () -> adapter.requireProcessingFence(42));
        var future = request(AccountDeletionRequest.Status.PROCESSING);
        ReflectionTestUtils.setField(future, "scheduledDeletionAt", NOW.plusSeconds(1));
        when(requests.findFirstByUserIdOrderByRequestedAtDescIdDesc(42L)).thenReturn(Optional.of(future));
        assertThrows(AccountDeletionException.class, () -> adapter.requireProcessingFence(42));
        verifyNoInteractions(sessions);
    }
    @Test void externalRevocationMustNotRunInsideRequestTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertThrows(IllegalStateException.class, () -> adapter.revokeForPendingRequest(42)); }
        finally { TransactionSynchronizationManager.clear(); }
        verifyNoInteractions(sessions);
    }
}
