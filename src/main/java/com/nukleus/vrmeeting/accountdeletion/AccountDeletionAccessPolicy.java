package com.nukleus.vrmeeting.accountdeletion;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Preparatory policy only: NOT registered as a filter/interceptor.
 * Existing public meeting/avatar/storage routes must first authenticate their caller.
 * The integration must use verified user IDs, fail closed on DB errors, and explicitly
 * permit authenticated status/cancel and secure reauthentication before invoking this policy.
 */
@Component
public class AccountDeletionAccessPolicy {
    private final AccountDeletionRepository repository;
    public AccountDeletionAccessPolicy(AccountDeletionRepository repository) { this.repository = repository; }
    public void requireNormalAccess(long authenticatedUserId) {
        if (repository.findFirstByUserIdOrderByRequestedAtDescIdDesc(authenticatedUserId)
                .filter(AccountDeletionRequest::active).isPresent()) {
            throw new AccountDeletionException(HttpStatus.FORBIDDEN, "ACCOUNT_PENDING_DELETION");
        }
    }
}
