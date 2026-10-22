package com.nukleus.vrmeeting.accountdeletion.phase2;

import com.nukleus.vrmeeting.accountdeletion.*;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Clock;
import static com.nukleus.vrmeeting.accountdeletion.phase2.Phase2Ports.*;

public final class SessionRevocationAdapter {
    private final Phase2Policy policy;
    private final SessionAuthority sessions;
    private final AccountDeletionRepository requests;
    private final Clock clock;
    public SessionRevocationAdapter(Phase2Policy policy, SessionAuthority sessions,
                                    AccountDeletionRepository requests, Clock clock) {
        this.policy = policy; this.sessions = sessions; this.requests = requests; this.clock = clock;
    }
    public void revokeForPendingRequest(long userId) {
        policy.requireAdapters();
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Session revocation must follow the request commit");
        }
        var request = ownedLatest(userId);
        switch (request.getStatus()) {
            case PENDING, PROCESSING, FAILED -> sessions.revokeAll(userId, request.getId());
            case CANCELLED, COMPLETED -> { /* Do not re-revoke on a delayed cancelled-request callback. */ }
        }
    }
    public long requireProcessingFence(long userId) {
        policy.requireAdapters();
        var request = ownedLatest(userId);
        if (request.getStatus() != AccountDeletionRequest.Status.PROCESSING ||
            request.getScheduledDeletionAt().isAfter(clock.instant())) {
            throw new AccountDeletionException(HttpStatus.CONFLICT, "CLAIM_LOST");
        }
        sessions.revokeAll(userId, request.getId());
        sessions.requireDurableWriteFence(userId, request.getId());
        return request.getId();
    }
    private AccountDeletionRequest ownedLatest(long userId) {
        if (userId <= 0) throw AccountDeletionException.integration();
        var request = requests.findFirstByUserIdOrderByRequestedAtDescIdDesc(userId)
            .orElseThrow(AccountDeletionException::integration);
        if (request.getId() == null || request.getUserId() == null || request.getUserId() != userId) {
            throw AccountDeletionException.integration();
        }
        return request;
    }
}
