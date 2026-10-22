package com.nukleus.vrmeeting.accountdeletion.phase2;

import com.nukleus.vrmeeting.accountdeletion.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import static com.nukleus.vrmeeting.accountdeletion.phase2.PendingDeletionAccessAdapter.Operation.*;

/** Future controller/ingress integration seam: no new endpoint or client-supplied identity parameter. */
public final class AuthenticatedDeletionFacade {
    private final PendingDeletionAccessAdapter access;
    private final AccountDeletionService phase1;
    public AuthenticatedDeletionFacade(PendingDeletionAccessAdapter access, AccountDeletionService phase1) {
        this.access = access; this.phase1 = phase1;
    }
    public AccountDeletionDtos.RequestResult request(Authentication authentication, boolean confirmed) {
        if (!confirmed) throw new AccountDeletionException(HttpStatus.BAD_REQUEST, "CONFIRMATION_REQUIRED");
        return phase1.request(access.authorize(authentication, REQUEST_DELETION));
    }
    public AccountDeletionDtos.StatusResponse status(Authentication authentication) {
        return phase1.status(access.authorize(authentication, DELETION_STATUS));
    }
    public AccountDeletionDtos.StatusResponse cancel(Authentication authentication) {
        // Existing service owns the transaction, exact deadline and cancellation/worker race checks.
        return phase1.cancel(access.authorize(authentication, CANCEL_DELETION));
    }
}
