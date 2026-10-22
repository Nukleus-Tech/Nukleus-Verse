package com.nukleus.vrmeeting.accountdeletion.phase2;

import com.nukleus.vrmeeting.accountdeletion.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import static com.nukleus.vrmeeting.accountdeletion.phase2.Phase2Ports.*;

/** Not installed on HTTP, meeting, avatar, upload, background or realtime routes. */
public final class PendingDeletionAccessAdapter {
    public enum Operation { NORMAL, REQUEST_DELETION, DELETION_STATUS, CANCEL_DELETION }
    private final EndUserIdentityAdapter identity;
    private final AccountDeletionRepository requests;
    public PendingDeletionAccessAdapter(EndUserIdentityAdapter identity, AccountDeletionRepository requests) {
        this.identity = identity; this.requests = requests;
    }
    public long authorize(Authentication authentication, Operation operation) {
        if (operation == null) throw AccountDeletionException.integration();
        var session = identity.identify(authentication,
            operation == Operation.REQUEST_DELETION || operation == Operation.CANCEL_DELETION);
        boolean restrictedOperation = operation == Operation.DELETION_STATUS || operation == Operation.CANCEL_DELETION;
        if (session.scope() == SessionScope.DELETION_ONLY && !restrictedOperation) deny();
        var request = requests.findFirstByUserIdOrderByRequestedAtDescIdDesc(session.userId()).orElse(null);
        if (request != null) {
            if (request.getUserId() == null || request.getUserId() != session.userId()) {
                throw AccountDeletionException.integration();
            }
            boolean pending = switch (request.getStatus()) {
                case PENDING, PROCESSING, FAILED -> true;
                case CANCELLED, COMPLETED -> false;
            };
            if (pending && !restrictedOperation) deny();
        }
        return session.userId();
    }
    private void deny() {
        throw new AccountDeletionException(HttpStatus.FORBIDDEN, "ACCOUNT_PENDING_DELETION");
    }
}
